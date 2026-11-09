
package raft04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nó de uma partição do sistema raft04.
 *
 * Execução:
 * PartitionNode <k> <i> [N]
 *
 * k = partição
 * i = identificador do nó (1 a GRUPO)
 * N = número total de partições (omissão: 3)
 *
 * Cada nó pode assumir os papéis:
 * SEGUIDOR, CANDIDATO ou LIDER.
 *
 * A eleição é gerida por RaftState.
 *
 * Todos os nós:
 * - Persistem os seus próprios registos.
 * - Participam nas eleições.
 * - Recebem os lotes do líder.
 * - Disponibilizam recuperação por TCP.
 *
 * Apenas o líder:
 * - Atribui novas sequências.
 * - Grava leituras IN.
 * - Publica lotes e heartbeats.
 */
public final class PartitionNode {

    // Monitor principal do nó
    private final Object LOCK = new Object();

    // Proteção do ficheiro de eventos
    private final Object LOCK_LOG = new Object();

    private final int k;
    private final int i;
    private final int N;

    private final int porto;
    private final String address;

    private final Path ficheiroRegistos;
    private final Path ficheiroEventos;
    private final Path ficheiroHB;

    private final Partitioner partitioner;
    private final IpfsPubSub ps;
    private final RaftState raft;

    // Log local, ordenado por sequência
    private final TreeMap<Long, SensorRecord> registos =
            new TreeMap<>();

    private long ultimoSeq;
    private long ultimoTermo;

    // Relógios utilizados quando o nó é líder
    private long lamport;
    private VectorClock vector;

    // Estado de publicação do líder
    private long hb;

    private List<SensorRecord> pendentes =
            new ArrayList<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tarefaHB;

    // Endereço do líder atualmente conhecido
    private String hostLider;
    private int portoLider = -1;

    // Último heartbeat aceite
    private long termoUltimoLote = -1;
    private int liderUltimoLote = 0;
    private long numeroUltimoHB = -1;

    /**
     * Inicialização de um nó.
     */
    public PartitionNode(
            int k,
            int i,
            int N,
            String api) throws IOException {

        if (N <= 0) {
            throw new IllegalArgumentException(
                "N deve ser positivo");
        }

        if (k < 0 || k >= N) {
            throw new IllegalArgumentException(
                "Partição inválida: " + k);
        }

        if (i < 1 || i > Wire.GRUPO) {
            throw new IllegalArgumentException(
                "Nó inválido: " + i);
        }

        this.k = k;
        this.i = i;
        this.N = N;

        this.porto = Wire.nodePort(k, i);
        this.address = "127.0.0.1";

        this.ficheiroRegistos = Paths.get(
            "no-p" + k + "-n" + i + ".txt");

        this.ficheiroEventos = Paths.get(
            "eventos-p" + k + "-n" + i + ".txt");

        this.ficheiroHB = Paths.get(
            "hb-p" + k + "-n" + i + ".txt");

        this.partitioner = new Partitioner(N);
        this.ps = new IpfsPubSub(api);

        // Carregar o log local antes de criar RaftState
        carregarRegistos();

        // RaftState recupera o termo e o voto
        // anteriormente persistidos
        this.raft = new RaftState(
            k,
            i,
            Wire.GRUPO,
            Wire.ELEICAO_MIN_MS,
            Wire.ELEICAO_MAX_MS,
            Paths.get(
                "estado-p" + k + "-n" + i + ".txt"),
            this::registar
        );

        this.lamport = 0;
        this.vector = new VectorClock(N);
        this.hb = 0;
    }

    /**
     * Escreve um evento na consola e no ficheiro.
     *
     * Formato:
     * instante;evento
     */
    private void registar(String evento) {

        String linha =
            System.currentTimeMillis() + ";" + evento;

        synchronized (LOCK_LOG) {

            System.out.println(linha);

            try {

                Files.writeString(
                    ficheiroEventos,
                    linha + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );

            } catch (IOException e) {

                System.err.println(
                    "Falha a escrever eventos: "
                    + e.getMessage());
            }
        }
    }

    /**
     * Carrega os registos persistidos.
     *
     * Exige sequências consecutivas a partir de 1.
     */
    private void carregarRegistos()
            throws IOException {

        synchronized (LOCK) {

            registos.clear();

            if (Files.exists(ficheiroRegistos)) {

                int numero = 0;

                for (String linha : Files.readAllLines(
                        ficheiroRegistos,
                        StandardCharsets.UTF_8)) {

                    numero++;

                    if (linha.isBlank()) {
                        continue;
                    }

                    SensorRecord r;

                    try {

                        r = SensorRecord.fromLine(linha, N);

                    } catch (IllegalArgumentException e) {

                        throw new IOException(
                            "Registo inválido na linha "
                            + numero, e);
                    }

                    long esperado =
                        registos.size() + 1L;

                    if (r.getSeq() != esperado) {

                        throw new IOException(
                            "Sequência inválida no ficheiro "
                            + ficheiroRegistos
                            + ": esperado=" + esperado
                            + " recebido=" + r.getSeq());
                    }

                    registos.put(r.getSeq(), r);
                }
            }

            atualizarUltimo();
        }
    }

    /**
     * Atualiza a última sequência e o último termo.
     *
     * Deve ser chamado dentro de LOCK.
     */
    private void atualizarUltimo() {

        if (registos.isEmpty()) {

            ultimoSeq = 0;
            ultimoTermo = 0;

        } else {

            SensorRecord r =
                registos.lastEntry().getValue();

            ultimoSeq = r.getSeq();
            ultimoTermo = r.getTerm();
        }
    }

    /**
     * Acrescenta um registo ao log local.
     *
     * Deve ser chamado dentro de LOCK.
     */
    private void acrescentar(SensorRecord r)
            throws IOException {

        if (r.getSeq() != ultimoSeq + 1) {

            throw new IOException(
                "Sequência fora de ordem: "
                + r.getSeq());
        }

        Files.writeString(
            ficheiroRegistos,
            r.toLine() + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );

        registos.put(r.getSeq(), r);

        ultimoSeq = r.getSeq();
        ultimoTermo = r.getTerm();
    }

    /**
     * Cancela a publicação periódica.
     *
     * Deve ser chamado dentro de LOCK.
     */
    private void cancelarHeartbeats() {

        if (tarefaHB != null) {

            tarefaHB.cancel(false);
            tarefaHB = null;
        }

        if (scheduler != null) {

            scheduler.shutdown();
            scheduler = null;
        }

        pendentes = new ArrayList<>();
    }

    /**
     * Verifica se o nó perdeu a liderança.
     *
     * Deve ser chamado dentro de LOCK.
     */
    private void verificarDemissao() {

        if (raft.termIfLeader() == -1
                && scheduler != null) {

            cancelarHeartbeats();
        }
    }

    /**
     * Passagem a líder.
     *
     * RaftState já atribuiu o papel LIDER
     * quando onVote() devolveu true.
     */
    private void tornarLider() {

        synchronized (LOCK) {

            if (raft.termIfLeader() == -1) {
                return;
            }

            cancelarHeartbeats();

            atualizarUltimo();

            if (registos.isEmpty()) {

                lamport = 0;
                vector = new VectorClock(N);

            } else {

                SensorRecord ultimo =
                    registos.lastEntry().getValue();

                lamport = ultimo.getLamport();
                vector = ultimo.getVector();
            }

            if (vector.get(k) != ultimoSeq) {

                registar(
                    "ERRO;p" + k
                    + ";vetor_inconsistente"
                    + ";seq=" + ultimoSeq
                    + ";V=" + vector);

                return;
            }

            hb = 0;
            pendentes = new ArrayList<>();

            scheduler =
                Executors.newSingleThreadScheduledExecutor();

            tarefaHB = scheduler.scheduleAtFixedRate(
                this::enviarLote,
                0,
                Wire.PERIODO_HB_MS,
                TimeUnit.MILLISECONDS
            );

            registar(
                "LIDER_ATIVO;p" + k
                + ";no=n" + i
                + ";termo=" + raft.term()
                + ";ultimoSeq=" + ultimoSeq
                + ";ultimoTermo=" + ultimoTermo);
        }
    }

    /**
     * Processa leituras IN.
     *
     * O tópico IN continua acessível
     * mesmo quando o nó está isolado.
     */
    private void onIn(String msg) {

        String sensor;
        double temperatura;
        long lRecebido;
        VectorClock vRecebido;

        try {

            if (!Wire.type(msg).equals("IN")) {
                return;
            }

            String[] f =
                Wire.body(msg).split(";", -1);

            if (f.length != 4) {

                throw new IllegalArgumentException(
                    "IN com número inválido de campos");
            }

            sensor = f[0].trim();

            if (sensor.isEmpty()) {

                throw new IllegalArgumentException(
                    "Sensor vazio");
            }

            temperatura =
                Double.parseDouble(f[1]);

            if (!Double.isFinite(temperatura)) {

                throw new IllegalArgumentException(
                    "Temperatura inválida");
            }

            lRecebido =
                Long.parseLong(f[2]);

            if (lRecebido < 0) {

                throw new IllegalArgumentException(
                    "Lamport negativo");
            }

            vRecebido =
                VectorClock.parse(f[3], N);

        } catch (IllegalArgumentException e) {

            registar(
                "REJEITADO;p" + k
                + ";tipo=IN"
                + ";erro=" + e.getMessage());

            return;
        }

        if (partitioner.of(sensor) != k) {

            registar(
                "PARTICAO_ERRADA;p" + k
                + ";sensor=" + sensor);

            return;
        }

        synchronized (LOCK) {

            long t = raft.termIfLeader();

            if (t == -1) {

                registar(
                    "IGNORADO;p" + k
                    + ";tipo=IN"
                    + ";sensor=" + sensor);

                return;
            }

            try {

                long novoSeq =
                    Math.addExact(ultimoSeq, 1);

                long novoLamport =
                    Math.addExact(
                        Math.max(lamport, lRecebido), 1);

                VectorClock novoVector =
                    vector.copy();

                novoVector.merge(vRecebido);
                novoVector.tick(k);

                if (novoVector.get(k) != novoSeq) {

                    registar(
                        "ERRO;p" + k
                        + ";vetor_inconsistente"
                        + ";seq=" + novoSeq);

                    return;
                }

                SensorRecord r = SensorRecord.now(
                    novoSeq,
                    t,
                    sensor,
                    temperatura,
                    novoLamport,
                    novoVector
                );

                acrescentar(r);

                lamport = novoLamport;
                vector = novoVector;

                pendentes.add(r);

                registar(
                    "EVENTO;p" + k
                    + ";seq=" + novoSeq
                    + ";termo=" + t
                    + ";L=" + lamport
                    + ";V=" + vector);

            } catch (IOException
                    | ArithmeticException e) {

                registar(
                    "ERRO;p" + k
                    + ";tipo=IN"
                    + ";erro=" + e.getMessage());
            }
        }
    }

    /**
     * Publica um lote ou heartbeat vazio.
     *
     * O bloqueio do nó é libertado antes
     * da publicação PubSub.
     *
     * O ficheiro de heartbeats apenas é atualizado
     * depois de uma publicação bem-sucedida.
     */
    private void enviarLote() {

        List<SensorRecord> loteRegistos;

        long termo;
        long numeroHB;
        long seqLote;
        long termoLote;

        synchronized (LOCK) {

            termo = raft.termIfLeader();

            if (termo == -1) {

                cancelarHeartbeats();
                return;
            }

            loteRegistos = pendentes;
            pendentes = new ArrayList<>();

            seqLote = ultimoSeq;
            termoLote = ultimoTermo;

            hb++;
            numeroHB = hb;
        }

        Batch lote = new Batch(
            termo,
            i,
            address,
            porto,
            numeroHB,
            seqLote,
            termoLote,
            loteRegistos
        );

        String mensagem = lote.encode();

        int n = loteRegistos.size();

        int bytes = mensagem.getBytes(
            StandardCharsets.UTF_8
        ).length;

        // Um nó isolado não publica LOTE.
        if (Wire.isolated(k, i)) {

            registar(
                "ISOLADO;p" + k
                + ";tipo=LOTE"
                + ";hb=" + numeroHB);

            return;
        }

        // Não publicar depois de perder a liderança.
        if (raft.termIfLeader() != termo) {
            return;
        }

        try {

            ps.publish(
                Wire.topicData(k),
                mensagem
            );

            // A publicação terminou com sucesso.
            long instante =
                System.currentTimeMillis();

            /*
             * CORREÇÃO 1:
             *
             * Formato exigido:
             *
             * termo;hb;instante;ultimoSeq;ultimoTermo;n;bytes
             *
             * Não incluir i.
             *
             * O identificador do nó já está no nome:
             * hb-p<k>-n<i>.txt
             */
            String linha =
                termo + ";"
                + numeroHB + ";"
                + instante + ";"
                + seqLote + ";"
                + termoLote + ";"
                + n + ";"
                + bytes
                + System.lineSeparator();

            try {

                Files.writeString(
                    ficheiroHB,
                    linha,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );

            } catch (IOException e) {

                registar(
                    "ERRO_HB;p" + k
                    + ";hb=" + numeroHB
                    + ";erro=" + e.getMessage());
            }

            registar(
                "HEARTBEAT;p" + k
                + ";termo=" + termo
                + ";lider=n" + i
                + ";hb=" + numeroHB
                + ";ultimoSeq=" + seqLote
                + ";ultimoTermo=" + termoLote
                + ";n=" + n);

        } catch (IOException e) {

            registar(
                "PUBLICACAO_FALHOU;p" + k
                + ";hb=" + numeroHB
                + ";erro=" + e.getMessage());

        } catch (InterruptedException e) {

            registar(
                "PUBLICACAO_INTERROMPIDA;p" + k
                + ";hb=" + numeroHB);

            Thread.currentThread().interrupt();
        }
    }

    /**
     * Processa mensagens de eleição.
     *
     * PEDIDO_VOTO:
     * - Atualiza o estado Raft.
     * - Responde com VOTO.
     *
     * VOTO:
     * - Observa sempre o termo recebido.
     * - Adota termos superiores mesmo quando
     *   o voto é dirigido a outro candidato.
     * - Apenas contabiliza votos dirigidos a si.
     */
    private void onElection(String msg) {

        if (Wire.isolated(k, i)) {
            return;
        }

        String tipo;

        try {

            tipo = Wire.type(msg);

        } catch (IllegalArgumentException e) {
            return;
        }

        if (tipo.equals("PEDIDO_VOTO")) {

            VoteRequest pedido;

            try {

                pedido = VoteRequest.decode(msg);

            } catch (IllegalArgumentException e) {

                registar(
                    "REJEITADO;p" + k
                    + ";tipo=PEDIDO_VOTO");

                return;
            }

            // Ignorar pedidos publicados pelo próprio nó.
            if (pedido.candidate == i) {
                return;
            }

            RaftState.Vote resposta;

            synchronized (LOCK) {

                resposta = raft.onVoteRequest(
                    pedido.term,
                    pedido.candidate,
                    pedido.lastSeq,
                    pedido.lastTerm,
                    ultimoSeq,
                    ultimoTermo
                );

                // Se foi observado um termo superior,
                // RaftState poderá ter demitido o líder.
                verificarDemissao();
            }

            VoteReply voto = new VoteReply(
                resposta.term,
                i,
                pedido.candidate,
                resposta.granted
            );

            if (Wire.isolated(k, i)) {
                return;
            }

            try {

                ps.publish(
                    Wire.topicElection(k),
                    voto.encode()
                );

            } catch (IOException
                    | InterruptedException e) {

                registar(
                    "ERRO_VOTO;p" + k
                    + ";erro=" + e.getMessage());

                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

        } else if (tipo.equals("VOTO")) {

            VoteReply voto;

            try {

                voto = VoteReply.decode(msg);

            } catch (IllegalArgumentException e) {

                registar(
                    "REJEITADO;p" + k
                    + ";tipo=VOTO");

                return;
            }

            // Ignorar mensagens publicadas pelo próprio nó.
            if (voto.voter == i) {
                return;
            }

            /*
             * CORREÇÃO 2:
             *
             * Não descartar votos dirigidos a outros
             * candidatos antes de observar o termo.
             *
             * RaftState.onVote() verifica primeiro
             * se o termo recebido é superior.
             *
             * Nesse caso, executa adoptTerm()
             * e devolve false.
             *
             * A verificação do candidato só acontece
             * depois da observação do termo.
             */
            synchronized (LOCK) {

                long termoAnterior = raft.term();

                boolean eleito = raft.onVote(
                    voto.term,
                    voto.voter,
                    voto.candidate,
                    voto.granted
                );

                // Cancelar os heartbeats se o nó
                // tiver perdido o papel de líder.
                verificarDemissao();

                if (raft.term() > termoAnterior) {

                    registar(
                        "TERMO_SUPERIOR_OBSERVADO;p" + k
                        + ";origem=VOTO"
                        + ";eleitor=n" + voto.voter
                        + ";candidato=n" + voto.candidate
                        + ";termo=" + voto.term);
                }

                // Apenas um voto dirigido a este nó
                // pode completar a sua eleição.
                if (voto.candidate != i) {
                    return;
                }

                if (eleito) {
                    tornarLider();
                }
            }
        }
    }

    /**
     * Processa um lote recebido.
     */
    private void onLote(String msg) {

        if (Wire.isolated(k, i)) {
            return;
        }

        Batch lote;

        try {

            lote = Batch.decode(msg, N);

        } catch (IllegalArgumentException e) {

            registar(
                "REJEITADO;p" + k
                + ";tipo=LOTE"
                + ";erro=" + e.getMessage());

            return;
        }

        // Ignorar lotes publicados pelo próprio nó.
        if (lote.leader == i) {
            return;
        }

        synchronized (LOCK) {

            if (!raft.onBatch(
                    lote.term,
                    lote.leader)) {

                registar(
                    "IGNORADO;p" + k
                    + ";tipo=LOTE"
                    + ";termo=" + lote.term);

                // onBatch pode ter observado um termo
                // superior e demitido o nó, mesmo
                // devolvendo false.
                verificarDemissao();

                return;
            }

            verificarDemissao();

            // Ignorar heartbeats antigos já recebidos.
            if (lote.term == termoUltimoLote
                    && lote.leader == liderUltimoLote
                    && lote.hb <= numeroUltimoHB) {

                registar(
                    "DUPLICADO_HB;p" + k
                    + ";termo=" + lote.term
                    + ";hb=" + lote.hb);

                return;
            }

            termoUltimoLote = lote.term;
            liderUltimoLote = lote.leader;
            numeroUltimoHB = lote.hb;

            hostLider = lote.address;
            portoLider = lote.port;

            registar(
                "RECEBIDO;p" + k
                + ";termo=" + lote.term
                + ";lider=n" + lote.leader
                + ";hb=" + lote.hb
                + ";ultimoSeq=" + lote.lastSeq
                + ";ultimoTermo=" + lote.lastTerm
                + ";n=" + lote.records.size());

            try {

                boolean divergencia = false;

                // Aplicar os registos pela ordem.
                for (SensorRecord r : lote.records) {

                    SensorRecord local =
                        registos.get(r.getSeq());

                    if (local != null) {

                        if (local.getTerm() == r.getTerm()) {

                            registar(
                                "DUPLICADO;p" + k
                                + ";seq=" + r.getSeq());

                        } else {

                            divergencia = true;
                            break;
                        }

                    } else if (r.getSeq() == ultimoSeq + 1) {

                        acrescentar(r);

                        registar(
                            "APLICADO;p" + k
                            + ";seq=" + r.getSeq()
                            + ";termo=" + r.getTerm());

                    } else {

                        // Lacuna: recuperar após o lote.
                        registar(
                            "LACUNA;p" + k
                            + ";seq=" + r.getSeq()
                            + ";falta=" + (ultimoSeq + 1));

                        break;
                    }
                }

                if (!divergencia) {

                    if (ultimoSeq > lote.lastSeq) {

                        divergencia = true;

                    } else if (ultimoSeq == lote.lastSeq
                            && ultimoTermo != lote.lastTerm) {

                        divergencia = true;
                    }
                }

                if (divergencia) {

                    resolverDivergencia(lote);
                    return;
                }

                // Recuperar registos em falta,
                // mesmo quando o lote tem n = 0.
                if (ultimoSeq < lote.lastSeq) {

                    recuperarAte(lote);
                }

                // Confirmar consistência após recuperação.
                if (ultimoSeq > lote.lastSeq
                        || (ultimoSeq == lote.lastSeq
                            && ultimoTermo != lote.lastTerm)) {

                    resolverDivergencia(lote);
                }

            } catch (IOException e) {

                registar(
                    "ERRO_APLICACAO;p" + k
                    + ";erro=" + e.getMessage());
            }
        }
    }

    /**
     * Recupera registos por TCP.
     *
     * O destinatário é o líder anunciado
     * no lote recebido.
     */
    private List<SensorRecord> pedirTCP(
            long de,
            long ate) throws IOException {

        if (de > ate) {
            return new ArrayList<>();
        }

        if (Wire.isolated(k, i)) {

            throw new IOException(
                "Nó local isolado");
        }

        if (hostLider == null || portoLider <= 0) {

            throw new IOException(
                "Líder desconhecido");
        }

        List<SensorRecord> recebidos =
            new ArrayList<>();

        try (Socket s = new Socket()) {

            s.connect(
                new InetSocketAddress(
                    hostLider,
                    portoLider
                ),
                Wire.T_TCP_MS
            );

            s.setSoTimeout(Wire.T_TCP_MS);

            PrintWriter out = new PrintWriter(
                new OutputStreamWriter(
                    s.getOutputStream(),
                    StandardCharsets.UTF_8
                ),
                true
            );

            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    s.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );

            out.println(
                "PEDIDO " + de + " " + ate);

            String linha;
            boolean terminou = false;

            while ((linha = in.readLine()) != null) {

                if (Wire.isolated(k, i)) {

                    throw new IOException(
                        "Nó isolado durante a recuperação");
                }

                String t = linha.trim();

                if (t.equals("FIM")) {

                    terminou = true;
                    break;
                }

                if (t.startsWith("ERRO")) {

                    throw new IOException(t);
                }

                SensorRecord r;

                try {

                    r = SensorRecord.fromLine(t, N);

                } catch (IllegalArgumentException e) {

                    throw new IOException(
                        "Registo TCP inválido", e);
                }

                long esperado =
                    de + recebidos.size();

                if (r.getSeq() != esperado
                        || r.getSeq() > ate) {

                    throw new IOException(
                        "Sequência TCP inesperada: "
                        + r.getSeq()
                        + ", esperado=" + esperado);
                }

                recebidos.add(r);
            }

            if (!terminou) {

                throw new IOException(
                    "Ligação terminada antes de FIM");
            }
        }

        long esperados = ate - de + 1;

        if (recebidos.size() != esperados) {

            throw new IOException(
                "Recuperação incompleta: esperados="
                + esperados
                + " recebidos=" + recebidos.size());
        }

        return recebidos;
    }

    /**
     * Recupera o sufixo em falta.
     *
     * Deve ser chamado dentro de LOCK.
     */
    private void recuperarAte(Batch lote)
            throws IOException {

        long de = ultimoSeq + 1;
        long ate = lote.lastSeq;

        if (de > ate) {
            return;
        }

        registar(
            "RECUPERACAO;p" + k
            + ";de=" + de
            + ";ate=" + ate);

        List<SensorRecord> remotos =
            pedirTCP(de, ate);

        for (SensorRecord r : remotos) {

            acrescentar(r);

            registar(
                "RECUPERADO;p" + k
                + ";seq=" + r.getSeq()
                + ";termo=" + r.getTerm());
        }
    }

    /**
     * Devolve o primeiro seq do bloco de termo
     * que contém a sequência indicada.
     */
    private long inicioBloco(long seq) {

        SensorRecord r =
            registos.get(seq);

        if (r == null) {
            return 1;
        }

        long termo = r.getTerm();
        long inicio = seq;

        while (inicio > 1) {

            SensorRecord anterior =
                registos.get(inicio - 1);

            if (anterior == null
                    || anterior.getTerm() != termo) {

                break;
            }

            inicio--;
        }

        return inicio;
    }

    /**
     * Resolve divergências entre logs.
     *
     * Recuam-se blocos de termo até encontrar
     * um prefixo coincidente com o líder.
     *
     * O sufixo divergente é substituído
     * atomicamente no ficheiro local.
     *
     * Deve ser chamado dentro de LOCK.
     */
    private void resolverDivergencia(Batch lote)
            throws IOException {

        if (Wire.isolated(k, i)) {

            throw new IOException(
                "Nó isolado");
        }

        long inicio;

        if (ultimoSeq == 0) {

            inicio = 1;

        } else {

            inicio = inicioBloco(ultimoSeq);
        }

        long primeiroDivergente = 1;

        List<SensorRecord> remotos =
            new ArrayList<>();

        while (true) {

            if (inicio > lote.lastSeq) {

                if (inicio == 1) {

                    primeiroDivergente = 1;
                    break;
                }

                inicio = inicioBloco(inicio - 1);
                continue;
            }

            registar(
                "RECUPERACAO;p" + k
                + ";de=" + inicio
                + ";ate=" + lote.lastSeq);

            remotos = pedirTCP(
                inicio,
                lote.lastSeq
            );

            if (remotos.isEmpty()) {

                if (inicio == 1) {

                    primeiroDivergente = 1;
                    break;
                }

                inicio = inicioBloco(inicio - 1);
                continue;
            }

            SensorRecord primeiroLocal =
                registos.get(inicio);

            SensorRecord primeiroRemoto =
                remotos.get(0);

            boolean coincide =
                primeiroLocal != null
                && primeiroLocal.getSeq()
                    == primeiroRemoto.getSeq()
                && primeiroLocal.getTerm()
                    == primeiroRemoto.getTerm();

            if (!coincide && inicio > 1) {

                inicio = inicioBloco(inicio - 1);
                continue;
            }

            primeiroDivergente =
                lote.lastSeq + 1;

            for (SensorRecord remoto : remotos) {

                SensorRecord local =
                    registos.get(remoto.getSeq());

                if (local == null
                        || local.getTerm()
                           != remoto.getTerm()) {

                    primeiroDivergente =
                        remoto.getSeq();

                    break;
                }
            }

            break;
        }

        if (lote.lastSeq == 0) {

            primeiroDivergente = 1;
            remotos = new ArrayList<>();
        }

        if (ultimoSeq > lote.lastSeq) {

            primeiroDivergente = Math.min(
                primeiroDivergente,
                lote.lastSeq + 1
            );
        }

        if (primeiroDivergente > ultimoSeq
                && primeiroDivergente > lote.lastSeq) {

            return;
        }

        TreeMap<Long, SensorRecord> novoLog =
            new TreeMap<>();

        // Preservar o prefixo coincidente.
        for (Map.Entry<Long, SensorRecord> entrada
                : registos.entrySet()) {

            if (entrada.getKey() >= primeiroDivergente) {
                break;
            }

            novoLog.put(
                entrada.getKey(),
                entrada.getValue()
            );
        }

        List<SensorRecord> sufixo;

        if (primeiroDivergente <= lote.lastSeq) {

            registar(
                "RECUPERACAO;p" + k
                + ";de=" + primeiroDivergente
                + ";ate=" + lote.lastSeq);

            sufixo = pedirTCP(
                primeiroDivergente,
                lote.lastSeq
            );

        } else {

            sufixo = new ArrayList<>();
        }

        for (SensorRecord r : sufixo) {
            novoLog.put(r.getSeq(), r);
        }

        // Identificar registos descartados.
        int descartados = 0;

        TreeSet<Long> termosDescartados =
            new TreeSet<>();

        for (Map.Entry<Long, SensorRecord> entrada
                : registos.tailMap(
                    primeiroDivergente, true
                ).entrySet()) {

            descartados++;

            termosDescartados.add(
                entrada.getValue().getTerm()
            );
        }

        // Verificar sequências consecutivas.
        long esperado = 1;

        for (Long seq : novoLog.keySet()) {

            if (seq != esperado) {

                throw new IOException(
                    "Novo log contém lacuna em seq="
                    + esperado);
            }

            esperado++;
        }

        Path temporario =
            ficheiroRegistos.resolveSibling(
                ficheiroRegistos.getFileName()
                + ".tmp"
            );

        StringBuilder conteudo =
            new StringBuilder();

        for (SensorRecord r : novoLog.values()) {

            conteudo.append(r.toLine())
                    .append(System.lineSeparator());
        }

        Files.writeString(
            temporario,
            conteudo.toString(),
            StandardCharsets.UTF_8
        );

        // Substituição atómica do ficheiro local.
        Files.move(
            temporario,
            ficheiroRegistos,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );

        // Atualizar a memória apenas após
        // a substituição bem-sucedida.
        registos.clear();
        registos.putAll(novoLog);

        atualizarUltimo();

        registar(
            "TRUNCADO;p" + k
            + ";de=" + primeiroDivergente
            + ";descartados=" + descartados
            + ";termos=" + termosDescartados);

        registar(
            "SINCRONIZADO;p" + k
            + ";ultimoSeq=" + ultimoSeq
            + ";ultimoTermo=" + ultimoTermo);
    }

    /**
     * Servidor TCP de recuperação.
     *
     * Qualquer nó pode disponibilizar registos,
     * mesmo sendo seguidor.
     */
    private void atenderTCP(Socket socket) {

        try (Socket s = socket) {

            s.setSoTimeout(Wire.T_TCP_MS);

            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    s.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );

            PrintWriter out = new PrintWriter(
                new OutputStreamWriter(
                    s.getOutputStream(),
                    StandardCharsets.UTF_8
                ),
                true
            );

            if (Wire.isolated(k, i)) {

                out.println("ERRO no isolado");
                return;
            }

            String pedido = in.readLine();

            if (pedido == null) {
                return;
            }

            String[] f =
                pedido.trim().split("\\s+");

            if (f.length != 3
                    || !f[0].equals("PEDIDO")) {

                out.println("ERRO pedido invalido");
                return;
            }

            long de;
            long ate;

            try {

                de = Long.parseLong(f[1]);
                ate = Long.parseLong(f[2]);

            } catch (NumberFormatException e) {

                out.println("ERRO intervalo invalido");
                return;
            }

            if (de <= 0 || ate < de) {

                out.println("ERRO intervalo invalido");
                return;
            }

            List<SensorRecord> resposta =
                new ArrayList<>();

            synchronized (LOCK) {

                long topo =
                    Math.min(ate, ultimoSeq);

                if (de <= topo) {

                    resposta.addAll(
                        registos.subMap(
                            de, true,
                            topo, true
                        ).values()
                    );
                }
            }

            for (SensorRecord r : resposta) {

                if (Wire.isolated(k, i)) {
                    return;
                }

                out.println(r.toLine());
            }

            if (!Wire.isolated(k, i)) {
                out.println("FIM");
            }

        } catch (IOException e) {

            registar(
                "ERRO_TCP;p" + k
                + ";erro=" + e.getMessage());
        }
    }

    /**
     * Inicia uma subscrição PubSub.
     */
    private void subscrever(
            String topico,
            java.util.function.Consumer<String> callback,
            String nome) {

        Thread t = new Thread(() -> {

            try {

                ps.subscribe(
                    topico,
                    callback::accept
                );

            } catch (IOException
                    | InterruptedException e) {

                registar(
                    "SUBSCRICAO_TERMINADA;p" + k
                    + ";topico=" + topico
                    + ";erro=" + e.getMessage());

                if (e instanceof InterruptedException) {

                    Thread.currentThread().interrupt();
                }
            }

        }, nome);

        t.start();
    }

    /**
     * Inicia a verificação periódica das eleições.
     *
     * A cada VERIFICACAO_MS chama raft.tick().
     */
    private void iniciarVerificacao() {

        Thread verificacao = new Thread(() -> {

            while (!Thread.currentThread().isInterrupted()) {

                try {

                    Thread.sleep(
                        Wire.VERIFICACAO_MS
                    );

                    long novoTermo;
                    long seqLocal;
                    long termoLocal;

                    synchronized (LOCK) {

                        novoTermo = raft.tick();

                        verificarDemissao();

                        seqLocal = ultimoSeq;
                        termoLocal = ultimoTermo;
                    }

                    if (novoTermo == -1) {
                        continue;
                    }

                    VoteRequest pedido =
                        new VoteRequest(
                            novoTermo,
                            i,
                            seqLocal,
                            termoLocal
                        );

                    // Nó isolado não publica
                    // mensagens de eleição.
                    if (Wire.isolated(k, i)) {

                        registar(
                            "ISOLADO;p" + k
                            + ";tipo=PEDIDO_VOTO"
                            + ";termo=" + novoTermo);

                        continue;
                    }

                    try {

                        ps.publish(
                            Wire.topicElection(k),
                            pedido.encode()
                        );

                    } catch (IOException e) {

                        registar(
                            "ERRO_ELEICAO;p" + k
                            + ";erro=" + e.getMessage());

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();
                    }

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                }
            }

        }, "verificacao-p" + k + "-n" + i);

        verificacao.setDaemon(true);
        verificacao.start();
    }

    /**
     * Arranca o nó.
     *
     * O servidor TCP é aberto antes
     * das subscrições PubSub.
     */
    public void iniciar() throws IOException {

        try (ServerSocket servidor =
                new ServerSocket(porto)) {

            registar(
                "ARRANQUE;p" + k
                + ";no=n" + i
                + ";papel=" + raft.role()
                + ";termo=" + raft.term()
                + ";ultimoSeq=" + ultimoSeq
                + ";ultimoTermo=" + ultimoTermo
                + ";porto=" + porto);

            // IN não é afetado pelo isolamento.
            subscrever(
                Wire.topicIn(k),
                this::onIn,
                "entrada-p" + k + "-n" + i
            );

            subscrever(
                Wire.topicData(k),
                this::onLote,
                "dados-p" + k + "-n" + i
            );

            subscrever(
                Wire.topicElection(k),
                this::onElection,
                "eleicao-p" + k + "-n" + i
            );

            iniciarVerificacao();

            registar(
                "PRONTO;p" + k
                + ";no=n" + i);

            // Servir pedidos TCP continuamente.
            while (true) {

                Socket s = servidor.accept();

                Thread t = new Thread(
                    () -> atenderTCP(s),
                    "tcp-p" + k + "-n" + i
                );

                t.start();
            }
        }
    }

    /**
     * Execução:
     * PartitionNode <k> <i> [N]
     */
    public static void main(String[] args)
            throws Exception {

        if (args.length < 2 || args.length > 3) {

            System.out.println(
                "Uso: PartitionNode <k> <i> [N]");

            return;
        }

        int k;
        int i;
        int N;

        try {

            k = Integer.parseInt(args[0]);
            i = Integer.parseInt(args[1]);

            N = args.length == 3
                ? Integer.parseInt(args[2])
                : 3;

        } catch (NumberFormatException e) {

            System.out.println(
                "Argumentos numéricos inválidos.");

            return;
        }

        String api =
            "http://127.0.0.1:5001/api/v0";

        PartitionNode no =
            new PartitionNode(k, i, N, api);

        no.iniciar();
    }
}
