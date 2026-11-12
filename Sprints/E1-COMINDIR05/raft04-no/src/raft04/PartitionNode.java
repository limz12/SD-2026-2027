package raft04;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Nó de partição (COMINDIR05): SEGUIDOR, CANDIDATO ou LÍDER, eleito por maioria
 * de votos com termos persistentes, ao estilo do RAFT.
 *
 * Uso: java [-Dsilencioso=true] [-Draft04.periodo=<ms>] [-Draft04.fator=<n>]
 *           [-Draft04.eleicaoMin=<ms>] [-Draft04.eleicaoMax=<ms>] -cp out
 *           raft04.PartitionNode <k> <i> [N] [portoApi=5001]
 *
 * k = partição de dados; i = índice do nó no grupo (1..G); N = número de
 * partições (por omissão 3). Os nove nós arrancam por qualquer ordem: a
 * primeira eleição decorre no arranque.
 *
 * Subscrições (uma thread cada, bloqueadas para sempre):
 * - raft04-in-p<k>: por cada IN válido, o nó entra na secção sincronizada e
 *   obtém termIfLeader(); se não for líder, assinala IGNORADO (a leitura não
 *   recebe seq); se for, atribui seq, carimba L e V, grava o termo do registo
 *   e põe-o em "pendentes".
 * - raft04-p<k>: por cada LOTE, chama onBatch(termo, lider); se devolver
 *   false, assinala IGNORADO e descarta-o; caso contrário aplica os registos
 *   como seguidor (duplicado / divergência / contíguo / lacuna) e, no fim do
 *   lote, recupera por TCP o que falta ou resolve divergências por truncagem.
 * - raft04-eleicao-p<k>: responde a PEDIDO_VOTO com VOTO (mesmo a recusar) e
 *   conta os VOTO que lhe são dirigidos; ao reunir maioria, torna-se líder.
 *
 * A thread de verificação chama tick() a cada VERIFICACAO_MS e publica
 * PEDIDO_VOTO com o termo devolvido, o seu id e o seu último registo.
 *
 * O servidor TCP de recuperação (Wire.nodePort(k, i)) serve os registos do
 * ficheiro do nó a qualquer outro nó, seja ou não líder (PEDIDO <de> <até>,
 * linhas de registo, FIM).
 *
 * PARTIÇÃO DE REDE (Wire.isolated): um nó isolado não publica nem processa
 * mensagens de raft04-p<k> nem de raft04-eleicao-p<k>, e as suas ligações TCP
 * de recuperação, como cliente ou servidor, falham. O tópico raft04-in-p<k>
 * NÃO é afetado: o líder isolado continua a aceitar leituras (que serão
 * descartadas — não há commit por maioria).
 *
 * LOCKS: o monitor do nó (LOCK) é o EXTERNO; dentro dele adquire-se o de
 * RaftState. Nenhuma operação os adquire pela ordem inversa: todas as chamadas
 * a RaftState (tick, onVoteRequest, onVote, onBatch, termIfLeader) acontecem
 * dentro de secções sincronizadas sobre LOCK, o que garante, por exemplo, que
 * um IN nunca recebe um seq com o termo antigo depois de o nó ter adotado um
 * termo superior (a demissão e a atribuição de seq nunca se intercalam). O
 * log (evento) nunca adquire LOCK nem o monitor de RaftState.
 *
 * Falha deliberada do ensaio 10 (FALSO por omissão):
 * -Draft04.falhaLVzero  o novo líder inicializa L e V a zero em vez de a
 * partir do último registo (manifesta-se como V[k] != seq na CausalCheck).
 */
public class PartitionNode {
    static final boolean SILENCIOSO = Boolean.getBoolean("silencioso");
    static final boolean FALHA_LVZERO = Boolean.getBoolean("raft04.falhaLVzero");

    // ---------------------------------------------------------------- identidade

    static int k; // partição de dados
    static int i; // índice do nó no grupo (1..GRUPO)
    static int n; // número de partições
    static IpfsPubSub ps;
    static Partitioner part;

    // monitor do nó (EXTERNO; ver comentário da classe)
    static final Object LOCK = new Object();

    static RaftState raft;

    // ---------------------------------------------------------------- registos (sob LOCK)

    static RecordFile file; // no-p<k>-n<i>.txt
    static final TreeMap<Long, Long> termosPorSeq = new TreeMap<>(); // seq -> termo (o que está no ficheiro)
    static SensorRecord ultimoRegisto; // último registo do ficheiro (ou null)
    static long ultimo; // último seq escrito no ficheiro
    static long ultimoTermo; // termo desse registo
    static final TreeMap<Long, SensorRecord> retidos = new TreeMap<>(); // lacunas à espera do seq anterior

    // ---------------------------------------------------------------- estado de líder (sob LOCK)

    static long seq; // último seq atribuído (igual a ultimo)
    static long lamport; // relógio de Lamport do líder
    static VectorClock vc; // relógio vetorial do líder, dimensão N
    static long hb; // nº do último lote fechado nesta liderança
    static List<SensorRecord> pendentes = new ArrayList<>(); // registos com seq ainda não publicados
    static ScheduledExecutorService agendador; // null enquanto não é líder

    // líder corrente (aprendido com cada LOTE válido), para recuperação por TCP
    static String liderEndereco;
    static int liderPorto = -1;

    // deduplicação de lotes aplicados (mesmo termo, hb repetido ou atrasado)
    static long hbVistoTermo = -1;
    static long hbVisto = 0;

    // estatísticas da rajada corrente (sob LOCK)
    static int contados, rejeitados;
    static long primeiroMs, ultimoMs;

    static void falar(String s) {
        if (!SILENCIOSO)
            System.out.println(s);
    }

    // Eventos do nó: consola + eventos-p<k>-n<i>.txt, "instante;evento;p<k>;detalhe".
    // synchronized (monitor da CLASSE, distinto de LOCK): é chamada pelas threads de
    // subscrição, pela de verificação e pelo log do RaftState. Nunca adquire LOCK.
    static synchronized void evento(String linha) {
        String l = System.currentTimeMillis() + ";" + linha;
        try {
            Files.writeString(Paths.get("eventos-p" + k + "-n" + i + ".txt"), l + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("ERRO a escrever o ficheiro de eventos: " + e.getMessage());
        }
        if (!SILENCIOSO && !linha.startsWith("RECEBIDO") && !linha.startsWith("IGNORADO"))
            System.out.println("[n" + i + "] " + l);
    }

    // ---------------------------------------------------------------- escrita de registos

    // ÚNICO caminho de escrita, chamado pelo líder (IN) e pelo seguidor (LOTE /
    // recuperação / truncagem): mantém o ficheiro, termosPorSeq, ultimo,
    // ultimoTermo e seq coerentes entre si. Chamar sob LOCK.
    static void escrever(SensorRecord r) throws IOException {
        file.append(r); // 1) escreve localmente (fonte da recuperação)
        termosPorSeq.put(r.getSeq(), r.getTerm());
        ultimoRegisto = r;
        ultimo = r.getSeq();
        ultimoTermo = r.getTerm();
        seq = r.getSeq(); // quando líder, seq == ultimo
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionNode <k> <i> [N] [portoApi]   ex.: PartitionNode 1 2 3 5001");
            return;
        }
        try {
            k = Integer.parseInt(args[0]);
            i = Integer.parseInt(args[1]);
            n = args.length > 2 ? Integer.parseInt(args[2]) : 3;
            part = new Partitioner(n);
            if (k < 0 || k >= n)
                throw new IllegalArgumentException();
            if (i < 1 || i > Wire.GRUPO)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            System.out.println("Argumentos inválidos: k em [0, N) e i em [1, " + Wire.GRUPO + "].");
            return;
        }
        String api = "http://127.0.0.1:" + (args.length > 3 ? args[3] : "5001") + "/api/v0";
        ps = new IpfsPubSub(api);
        file = new RecordFile("no-p" + k + "-n" + i + ".txt", n);

        // 1. lê o ficheiro local, se existir, e determina ultimoSeq e ultimoTermo
        try {
            for (SensorRecord r : file.all()) {
                termosPorSeq.put(r.getSeq(), r.getTerm());
                ultimoRegisto = r;
            }
            if (ultimoRegisto != null) {
                ultimo = ultimoRegisto.getSeq();
                ultimoTermo = ultimoRegisto.getTerm();
                seq = ultimo;
                if (ultimoRegisto.getVector().get(k) != ultimo) {
                    // ficheiro inconsistente (ex.: pastas de ensaios misturadas):
                    // assinala-se e continua-se; o invariant V[k] == seq aparece na CausalCheck
                    System.out.println("AVISO: última linha com V[" + k + "]="
                            + ultimoRegisto.getVector().get(k) + " != seq=" + ultimo
                            + " (ficheiro de outro ensaio?)");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }

        // 2. cria o RaftState, como seguidor e com o termo gravado
        try {
            raft = new RaftState(k, i, Wire.GRUPO, Wire.ELEICAO_MIN_MS, Wire.ELEICAO_MAX_MS,
                    Paths.get("estado-p" + k + "-n" + i + ".txt"), PartitionNode::evento);
        } catch (IOException e) {
            System.out.println("IO (estado): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }

        int porto = Wire.nodePort(k, i);
        evento("ARRANQUE;p" + k + ";termo=" + raft.term() + ";ultimoSeq=" + ultimo + ";ultimoTermo=" + ultimoTermo);

        // O porto é aberto ANTES de se aceitar qualquer recuperação: um nó que
        // anuncia um porto que não serve é pior do que um nó que não arranca.
        try (ServerSocket server = new ServerSocket(porto)) {
            System.out.println("Nó n" + i + " de p" + k + "/" + n + " pronto (seguidor do termo " + raft.term()
                    + "): recuperação em 127.0.0.1:" + porto
                    + " | eleição em " + Wire.topicElection(k) + " | entrada em " + Wire.topicIn(k));
            System.out.println("Δ=" + Wire.PERIODO_HB_MS + " ms | prazo de eleição em [" + Wire.ELEICAO_MIN_MS + ", "
                    + Wire.ELEICAO_MAX_MS + "] ms | ficheiro até seq=" + ultimo + " (termo " + ultimoTermo + ")");

            Thread entrada = new Thread(() -> subscrever(Wire.topicIn(k), PartitionNode::onIn), "entrada");
            Thread dados = new Thread(() -> subscrever(Wire.topicData(k), PartitionNode::onLote), "dados");
            Thread eleicao = new Thread(() -> subscrever(Wire.topicElection(k), PartitionNode::onEleicao), "eleicao");
            entrada.start();
            dados.start();
            eleicao.start();

            // thread de verificação: a cada VERIFICACAO_MS, tick() e, se houver
            // candidatura, publica o PEDIDO_VOTO com o termo e o último registo
            Thread verificacao = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(Wire.VERIFICACAO_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long t;
                    long uSeq, uTermo;
                    synchronized (LOCK) {
                        t = raft.tick();
                        if (t == -1)
                            continue;
                        // ensaio 10 (falhaMaioria): o tick pode promover o nó a
                        // líder com o auto-voto apenas
                        if (raft.role() == RaftState.Role.LIDER)
                            tornarLider();
                        uSeq = ultimo;
                        uTermo = ultimoTermo;
                    }
                    if (Wire.isolated(k, i))
                        continue; // partição de rede: não publica (o termo já avançou)
                    try {
                        ps.publish(Wire.topicElection(k), new VoteRequest(t, i, uSeq, uTermo).encode());
                    } catch (IOException | InterruptedException e) {
                        System.out.println("FALHA A PUBLICAR PEDIDO_VOTO: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                    }
                }
            }, "verificacao");
            verificacao.setDaemon(true);
            verificacao.start();

            Thread monitor = new Thread(PartitionNode::resumos, "resumos");
            monitor.setDaemon(true);
            monitor.start();

            // RECUPERAÇÃO (thread principal): atende os pedidos dos outros nós
            // UM DE CADA VEZ, lendo o ficheiro com range().
            while (true) {
                try (Socket s = server.accept(); // BLOQUEIA até outro nó se ligar
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter out = new PrintWriter(
                                new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS);
                    if (Wire.isolated(k, i)) {
                        // partição de rede: a ligação de recuperação FALHA
                        out.println("ERRO nó isolado (isolar-p" + k + "-n" + i + ")");
                        continue;
                    }
                    atender(in.readLine(), s, out);
                } catch (IOException e) {
                    // uma falha numa ligação de recuperação termina apenas ESSA ligação
                    System.out.println("Recuperação interrompida: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // Ex.: BindException (porto de recuperação ocupado por outro processo)
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    static void subscrever(String topico, java.util.function.Consumer<String> handler) {
        try {
            ps.subscribe(topico, msg -> { // BLOQUEIA aqui: a resposta HTTP nunca termina
                try {
                    handler.accept(msg);
                } catch (Throwable e) {
                    System.out.println("ERRO em " + topico + ": " + e.getClass().getSimpleName() + ": "
                            + e.getMessage());
                }
            });
            System.out.println("Subscrição de " + topico + " terminada.");
        } catch (IOException | InterruptedException e) {
            System.out.println("SUBSCRIÇÃO FALHOU (" + topico + "): " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        // Um nó que já não consigo subscrever está partido para este tópico.
        System.exit(1);
    }

    // ---------------------------------------------------------------- eleição

    static void onEleicao(String msg) {
        if (Wire.isolated(k, i))
            return; // partição de rede: não processa eleições
        String tipo = Wire.type(msg);
        if (tipo.equals("PEDIDO_VOTO")) {
            VoteRequest vr = VoteRequest.decode(msg);
            if (vr.candidate == i)
                return; // ignora as mensagens que ele próprio publicou
            RaftState.Vote v;
            synchronized (LOCK) {
                v = raft.onVoteRequest(vr.term, vr.candidate, vr.lastSeq, vr.lastTerm, ultimo, ultimoTermo);
            }
            // publica VOTO com o termo e a decisão devolvidos, MESMO a recusar:
            // o termo da resposta pode forçar o candidato a demitir-se
            try {
                ps.publish(Wire.topicElection(k),
                        new VoteReply(v.term, i, vr.candidate, v.granted).encode());
            } catch (IOException | InterruptedException e) {
                System.out.println("FALHA A PUBLICAR VOTO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } else if (tipo.equals("VOTO")) {
            VoteReply vr = VoteReply.decode(msg);
            if (vr.eleitor == i || vr.candidate != i)
                return; // próprio voto, ou voto dirigido a outro candidato
            boolean liderAgora;
            synchronized (LOCK) {
                liderAgora = raft.onVote(vr.term, vr.eleitor, vr.candidate, vr.granted);
            }
            if (liderAgora)
                tornarLider(); // passagem a líder do passo 4
        }
    }

    // Passagem a líder: numa secção sincronizada com o monitor do nó,
    // inicializa seq com o ultimoSeq local, L e V a partir do último registo,
    // hb = 0 e pendentes vazia, e inicia o agendador de enviarLote().
    // Idempotente: se já tem o agendador a correr (já é líder), não faz nada.
    static void tornarLider() {
        synchronized (LOCK) {
            if (agendador != null)
                return;
            seq = ultimo;
            if (FALHA_LVZERO || ultimoRegisto == null) {
                // ensaio 10 (falhaLVzero): L e V a zero em vez de a partir do último
                // registo. No código correto, sem registo anterior, zero É o valor
                // certo (partição nova).
                lamport = 0;
                vc = new VectorClock(n);
                if (FALHA_LVZERO && ultimoRegisto != null)
                    evento("ERRO;p" + k + ";inicializou L=0 e V=0 com ultimoSeq=" + ultimo + " (falha introduzida)");
            } else {
                lamport = ultimoRegisto.getLamport();
                vc = VectorClock.parse(ultimoRegisto.getVector().toString(), n);
            }
            hb = 0;
            pendentes = new ArrayList<>();
            agendador = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "heartbeat");
                t.setDaemon(true);
                return t;
            });
            // scheduleAtFixedRate: um LOTE imediato (afirma a liderança) e depois um
            // a cada Δ; se o processo ficar suspenso, as execuções em atraso são
            // feitas de seguida.
            agendador.scheduleAtFixedRate(PartitionNode::enviarLote, 0, Wire.PERIODO_HB_MS,
                    TimeUnit.MILLISECONDS);
        }
        System.out.println("[n" + i + "] LÍDER de p" + k + ": seq=" + seq + " L=" + lamport + " V=" + vc);
    }

    // ---------------------------------------------------------------- entrada (IN)

    static void onIn(String msg) {
        long agora = System.currentTimeMillis();
        String sensor;
        double temp;
        long lmsg;
        VectorClock vmsg;
        try {
            if (!Wire.type(msg).equals("IN"))
                throw new IllegalArgumentException("tipo");
            String[] f = Wire.body(msg).split(";");
            if (f.length != 4 || f[0].isBlank())
                throw new IllegalArgumentException("campos");
            sensor = f[0];
            temp = Double.parseDouble(f[1]);
            lmsg = Long.parseLong(f[2]);
            vmsg = VectorClock.parse(f[3], n); // dimensão != N -> rejeitado
        } catch (IllegalArgumentException e) { // inclui NumberFormatException
            synchronized (LOCK) {
                rejeitados++;
            }
            System.out.println("REJEITADO: " + msg + " (" + e.getMessage() + ")");
            return;
        }

        int j = part.of(sensor);
        if (j != k) {
            // Só acontece com N diferente entre produtor e nós (ou um publicador a
            // ignorar a função de partição). A verificação TEM de estar aqui.
            synchronized (LOCK) {
                rejeitados++;
            }
            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + j);
            return;
        }

        SensorRecord r;
        synchronized (LOCK) {
            long t = raft.termIfLeader(); // LOCK -> RaftState
            if (t == -1) {
                // não é líder (seguidor ou candidato): a leitura não recebe seq.
                // É assim que as leituras enviadas durante uma eleição se perdem.
                evento("IGNORADO;p" + k + ";leitura=" + sensor);
                return;
            }
            // UMA só secção sincronizada: papel + relógios + seq + append. O t é
            // lido DENTRO da secção: um nó que se demite entre o termIfLeader() e
            // o append nunca atribui um seq com o termo antigo.
            long proximo = seq + 1;
            long novoL = Math.max(lamport, lmsg) + 1; // Lamport: max(L, L_msg) + 1
            VectorClock novoV = vc.copy();
            novoV.merge(vmsg); // vetorial: máximo componente a componente
            novoV.tick(k); // ... e depois V[k] = V[k] + 1
            r = SensorRecord.now(proximo, t, sensor, temp, novoL, novoV.copy()); // copy(): instantâneo
            try {
                escrever(r); // 1) escreve localmente (fonte da recuperação)
            } catch (IOException e) {
                // falha ao escrever no ficheiro: a leitura NÃO foi registada
                System.out.println("FALHA A ESCREVER: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return;
            }
            lamport = novoL;
            vc = novoV;
            pendentes.add(r); // fica à espera do próximo lote, SÓ depois de estar no ficheiro
            if (contados == 0)
                primeiroMs = agora;
            contados++;
            ultimoMs = System.currentTimeMillis();
        }
        falar("EVENTO: seq=" + r.getSeq() + " termo=" + r.getTerm() + " L=" + r.getLamport() + " V=" + r.getVector()
                + " (pendente)");
    }

    // ---------------------------------------------------------------- lote publicado pelo líder

    static void onLote(String msg) {
        if (Wire.isolated(k, i))
            return; // partição de rede: não processa lotes
        Batch b;
        try {
            b = Batch.decode(msg, n); // valida o cabeçalho e o nº de linhas
        } catch (IllegalArgumentException e) {
            System.out.println("[n" + i + "] LOTE ilegível: " + msg.split("\n", 2)[0] + " (" + e.getMessage() + ")");
            return;
        }
        if (b.leader == i)
            return; // a sua própria publicação
        synchronized (LOCK) {
            if (b.term == hbVistoTermo && b.hb <= hbVisto) {
                falar("LOTE duplicado ou atrasado: termo=" + b.term + " hb=" + b.hb);
                return; // um lote antigo poderia simular uma divergência falsa
            }
            boolean aplicar = raft.onBatch(b.term, b.leader); // pode demitir o nó
            if (!aplicar) {
                evento("IGNORADO;p" + k + ";termo=" + b.term + " (obsoleto; meu termo=" + raft.term() + ")");
                return;
            }
            hbVistoTermo = b.term;
            hbVisto = b.hb;
            // o endereço do líder é aprendido AQUI, em cada lote válido
            liderEndereco = b.address;
            liderPorto = b.port;

            String faixa = b.records.isEmpty() ? "-"
                    : b.records.get(0).getSeq() + ".." + b.records.get(b.records.size() - 1).getSeq();
            evento("RECEBIDO;p" + k + ";termo=" + b.term + ",hb=" + b.hb + ",lider=n" + b.leader + ",ultimoSeq="
                    + b.lastSeq + ",ultimoTermo=" + b.lastTerm + ",n=" + b.records.size() + ",seq=" + faixa);

            boolean divergente = false;
            for (SensorRecord r : b.records) {
                if (aplicarSeguidor(r) == DIVERGENTE) {
                    divergente = true;
                    break; // resolve-se a seguir, com o procedimento de blocos
                }
                if (retidos.size() > Wire.LIMITE_RETIDOS) {
                    recuperar(ultimo + 1, retidos.firstKey() - 1); // regra da COMINDIR02
                }
            }
            // Fim do lote: compara o último registo local com ultimoSeq e
            // ultimoTermo do cabeçalho. Se o nó tem registos para além do
            // cabeçalho, ou o termo do seu último registo difere do
            // ultimoTermo do líder (incluindo quando faltam registos: o
            // prefixo local pode estar divergente — é o caso do antigo líder
            // que regressa e cujos registos por publicar foram reatribuídos
            // pelo novo líder), corre o procedimento de blocos, que recupera
            // os registos que faltam e trunca APENAS se encontrar diferença.
            // Caso contrário (prefixo com o mesmo termo, ou ficheiro vazio),
            // recuperação simples por TCP.
            if (divergente || ultimo > b.lastSeq || (ultimo > 0 && ultimoTermo != b.lastTerm)) {
                resolverDivergencia(b.lastSeq, b.lastTerm);
            } else if (ultimo < b.lastSeq) {
                // faltam registos até ao ultimoSeq do cabeçalho
                evento("RECUPERACAO;p" + k + ";de=" + (ultimo + 1) + ";ate=" + b.lastSeq);
                recuperar(ultimo + 1, b.lastSeq);
            }
            falar("LOTE termo=" + b.term + " hb=" + b.hb + " n=" + b.records.size() + " seq=" + faixa
                    + " ultimoSeq=" + b.lastSeq + " | ficheiro até " + ultimo + " (termo " + ultimoTermo + ")"
                    + (retidos.isEmpty() ? "" : " | retidos " + retidos.size()));
        }
    }

    // termo do registo com este seq no ficheiro local (null se não existe)
    static Long termoDe(long seq) {
        return termosPorSeq.get(seq);
    }

    static final int DUPLICADO = 0, ESCRITO = 1, DIVERGENTE = 2, RETIDO = 3;

    // Aplicação de um registo pelo seguidor (chamar sob LOCK):
    // - seq que já tem e o MESMO termo: duplicado, ignorado;
    // - seq que já tem e termo DIFERENTE: divergência;
    // - seq == último local + 1: acrescentado;
    // - seq superior: lacuna, retido (ou recuperada por TCP).
    static int aplicarSeguidor(SensorRecord r) {
        long s = r.getSeq();
        Long meu = termoDe(s);
        if (meu != null) {
            if (meu.longValue() == r.getTerm()) {
                falar("DUPLICADO: " + s);
                return DUPLICADO;
            }
            falar("DIVERGÊNCIA: seq=" + s + " meu termo=" + meu + " ≠ termo=" + r.getTerm());
            return DIVERGENTE;
        }
        if (s == ultimo + 1) {
            escreverSeguidor(r, "Aplicado");
            while (!retidos.isEmpty() && retidos.firstKey() == ultimo + 1) {
                escreverSeguidor(retidos.pollFirstEntry().getValue(), "Aplicado (retido)");
            }
            return ESCRITO;
        }
        retidos.put(s, r); // nunca se escreve fora de ordem
        falar("EM ESPERA: " + s + " (falta o registo " + (ultimo + 1) + ")");
        return RETIDO;
    }

    static void escreverSeguidor(SensorRecord r, String etiqueta) {
        try {
            escrever(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // não pode ser confundido com uma falha de rede
        }
        falar(etiqueta + ": " + r.getSeq() + " (termo " + r.getTerm() + ")");
    }

    // ---------------------------------------------------------------- recuperação por TCP (cliente)

    // Corre NA PRÓPRIA thread de dados: enquanto dura, esta partição deixa de
    // processar lotes seguintes. Devolve true se encontrou uma divergência.
    static boolean recuperar(long de, long ate) {
        if (liderEndereco == null || ate < de)
            return false;
        if (Wire.isolated(k, i)) {
            falar("RECUPERAÇÃO FALHOU: nó isolado");
            return false;
        }
        falar("RECUPERAÇÃO: " + de + ".." + ate + " pedida a " + liderEndereco + ":" + liderPorto);
        List<SensorRecord> regs = pedirRegistos(de, ate);
        if (regs == null) {
            falar("RECUPERAÇÃO FALHOU (ligação ao líder)");
            return false;
        }
        boolean divergente = false;
        for (SensorRecord r : regs) {
            if (aplicarSeguidor(r) == DIVERGENTE) {
                divergente = true;
                break;
            }
        }
        return divergente;
    }

    // PEDIDO <de> <até> ao líder corrente (aprendido com o último LOTE).
    // Devolve null se a ligação falhar, ou os registos recebidos (podem ser
    // menos do que os pedidos: o servidor limita ao que tem).
    static List<SensorRecord> pedirRegistos(long de, long ate) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(liderEndereco, liderPorto), Wire.T_TCP_MS); // BLOQUEIA até ser aceite
            s.setSoTimeout(Wire.T_TCP_MS); // readLine deixa de bloquear para sempre
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8),
                    true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("PEDIDO " + de + " " + ate);
            List<SensorRecord> regs = new ArrayList<>();
            boolean fim = false;
            String linha;
            while ((linha = in.readLine()) != null) { // BLOQUEIA à espera de cada linha
                if (linha.equals("FIM")) {
                    fim = true;
                    break;
                }
                if (linha.startsWith("ERRO")) {
                    throw new IOException("o nó respondeu: " + linha);
                }
                regs.add(SensorRecord.fromLine(linha, n)); // MESMA regra que os registos do lote
            }
            if (!fim) {
                throw new IOException("resposta interrompida antes de FIM");
            }
            return regs;
        } catch (IOException | IllegalArgumentException e) {
            falar("RECUPERAÇÃO FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- divergências (truncagem)

    // Resolve uma divergência a partir do registo do líder (chamar sob LOCK):
    // 1. calcula os blocos de termo do ficheiro local (sequências finais de
    //    registos com o mesmo termo);
    // 2. pede ao líder os registos a partir do primeiro seq do ÚLTIMO bloco e
    //    recua um bloco de cada vez enquanto o primeiro registo recebido não
    //    coincidir com o seu;
    // 3. trunca o ficheiro a partir do primeiro seq em que encontra uma
    //    diferença (reescreve de forma atómica);
    // 4. aplica os registos do líder a partir desse seq.
    static void resolverDivergencia(long ultimoSeqLider, long ultimoTermoLider) {
        if (liderEndereco == null)
            return;
        falar("DIVERGÊNCIA com o líder: ficheiro até seq=" + ultimo + " (termo " + ultimoTermo
                + "), líder até seq=" + ultimoSeqLider + " (termo " + ultimoTermoLider + ")");

        List<SensorRecord> meus = new ArrayList<>();
        try {
            meus = file.all();
        } catch (IOException e) {
            falar("DIVERGÊNCIA FALHOU ao ler o ficheiro: " + e.getMessage());
            return;
        }
        if (meus.isEmpty())
            return; // sem registos não há divergência a resolver
        TreeMap<Long, SensorRecord> meusPorSeq = new TreeMap<>();
        for (SensorRecord r : meus)
            meusPorSeq.put(r.getSeq(), r);

        // blocos de termo: [primeiroSeq, últimoSeq] com o mesmo termo
        List<long[]> blocos = new ArrayList<>();
        long inicio = meus.get(0).getSeq(), anterior = inicio, termoBloco = meus.get(0).getTerm();
        for (SensorRecord r : meus) {
            if (r.getTerm() != termoBloco) {
                blocos.add(new long[] { inicio, anterior, termoBloco });
                inicio = r.getSeq();
                termoBloco = r.getTerm();
            }
            anterior = r.getSeq();
        }
        blocos.add(new long[] { inicio, anterior, termoBloco });

        // âncora: o último bloco cujo primeiro registo coincide com o do líder
        long ancoraDe = -1;
        List<SensorRecord> doLider = null;
        for (int b = blocos.size() - 1; b >= 0; b--) {
            long de = blocos.get(b)[0];
            List<SensorRecord> recebidos = pedirRegistos(de, Long.MAX_VALUE);
            if (recebidos == null) {
                falar("DIVERGÊNCIA FALHOU: líder inacessível; tenta-se de novo no próximo lote");
                return; // NÃO se trunca sem confirmar o registo do líder
            }
            if (recebidos.isEmpty() || recebidos.get(0).getSeq() != de)
                continue; // o líder não tem esse seq: recua um bloco
            SensorRecord meu = meusPorSeq.get(de);
            if (meu != null && meu.toLine().equals(recebidos.get(0).toLine())) {
                ancoraDe = de;
                doLider = recebidos;
                break;
            }
        }
        if (ancoraDe == -1) {
            // nenhum bloco coincide: recua até ao primeiro registo
            ancoraDe = 1;
            doLider = pedirRegistos(1, Long.MAX_VALUE);
            if (doLider == null) {
                falar("DIVERGÊNCIA FALHOU: líder inacessível; tenta-se de novo no próximo lote");
                return;
            }
        }

        // primeiro seq divergente a partir da âncora
        TreeMap<Long, SensorRecord> liderPorSeq = new TreeMap<>();
        for (SensorRecord r : doLider)
            liderPorSeq.put(r.getSeq(), r);
        long corte = ancoraDe;
        while (true) {
            SensorRecord meu = meusPorSeq.get(corte);
            SensorRecord dele = liderPorSeq.get(corte);
            if (meu == null || dele == null || !meu.toLine().equals(dele.toLine()))
                break; // trunca a partir daqui (falta-me, falta-lhe, ou diferem)
            corte++;
        }

        // registos descartados: os meus com seq >= corte
        List<SensorRecord> descartados = new ArrayList<>(meusPorSeq.tailMap(corte).values());
        if (descartados.isEmpty() && corte > ultimo) {
            // nada a truncar: é apenas aplicação dos registos que faltam
            evento("RECUPERACAO;p" + k + ";de=" + corte + ";ate=" + ultimoSeqLider);
            falar("DIVERGÊNCIA resolvida sem truncagem: coincide até seq=" + (corte - 1));
        } else {
            try {
                file.truncateFrom(corte); // reescrita atómica sem os seq >= corte
            } catch (IOException e) {
                falar("DIVERGÊNCIA FALHOU ao truncar: " + e.getMessage());
                return;
            }
            final long corteFinal = corte;
            termosPorSeq.keySet().removeIf(s -> s >= corteFinal);
            retidos.clear();
            // repõe o estado local a partir do último registo mantido
            Map.Entry<Long, Long> ultimaEntrada = termosPorSeq.lastEntry();
            ultimo = ultimaEntrada == null ? 0 : ultimaEntrada.getKey();
            ultimoTermo = ultimaEntrada == null ? 0 : ultimaEntrada.getValue();
            ultimoRegisto = ultimaEntrada == null ? null : meusPorSeq.get(ultimo);
            seq = ultimo;
        }

        // aplica os registos do líder a partir do corte
        int aplicados = 0;
        for (SensorRecord r : doLider) {
            if (r.getSeq() >= corte) {
                escreverSeguidor(r, "Aplicado (do líder)");
                aplicados++;
            }
        }

        StringBuilder termos = new StringBuilder();
        for (SensorRecord r : descartados) {
            if (termos.length() > 0)
                termos.append(',');
            termos.append(r.getTerm());
        }
        StringBuilder leituras = new StringBuilder();
        for (SensorRecord r : descartados) {
            if (leituras.length() > 0)
               leituras.append(',');
            leituras.append(r.getSensorId()).append(':').append(r.getTemperature());
        }
        evento("TRUNCADO;p" + k + ";de=" + corte + ";descartados=" + descartados.size()
                + ";termos=" + termos + ";leituras=" + leituras);
        falar("TRUNCADO de=" + corte + " descartados=" + descartados.size() + " aplicados=" + aplicados
                + " | ficheiro até " + ultimo + " (termo " + ultimoTermo + ")");
    }

    // ---------------------------------------------------------------- servidor TCP de recuperação

    static void atender(String pedido, Socket s, PrintWriter out) throws IOException {
        String remoto = s.getInetAddress().getHostAddress() + ":" + s.getPort();
        if (pedido == null) {
            System.out.println("Recuperação: " + remoto + " fechou a ligação sem enviar o pedido");
            return;
        }
        String[] p = pedido.trim().split("\\s+");
        long de, ate;
        try {
            if (p.length != 3 || !p[0].equals("PEDIDO")) {
                throw new IllegalArgumentException("esperado: PEDIDO <de> <até>");
            }
            de = Long.parseLong(p[1]);
            ate = Long.parseLong(p[2]);
        } catch (IllegalArgumentException e) { // inclui NumberFormatException
            out.println("ERRO pedido mal formado");
            System.out.println("Recuperação: pedido mal formado de " + remoto + ": \"" + pedido + "\"");
            return;
        }
        if (de < 1 || de > ate) {
            out.println("ERRO intervalo inválido (de=" + de + ", até=" + ate + ")");
            System.out.println("Recuperação: intervalo inválido de " + remoto + ": " + de + ".." + ate);
            return;
        }
        long limite;
        synchronized (LOCK) {
            limite = ultimo; // o ficheiro tem, no mínimo, tudo até este seq
        }
        ate = Math.min(ate, limite); // nunca se devolve o que ainda não existe

        List<SensorRecord> regs = file.range(de, ate); // lê o ficheiro DESTE nó
        for (SensorRecord r : regs) {
            out.println(r.toLine());
        }
        out.println("FIM");
        if (out.checkError()) {
            throw new IOException("o pedido fechou a ligação a meio da resposta");
        }
        System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " para " + remoto + " (" + regs.size() + " registos)");
    }

    // ---------------------------------------------------------------- lote do líder (publicação)

    // Fecha e publica um lote (que é também o heartbeat). Executada pela thread
    // "heartbeat" do agendador. Se o nó já não for líder (termIfLeader() == -1),
    // cancela o agendamento: foi demitido por um termo superior.
    static void enviarLote() {
        try {
            List<SensorRecord> lote;
            long t, hbAtual, uSeq, uTermo;
            synchronized (LOCK) {
                t = raft.termIfLeader();
                if (t == -1) {
                    if (agendador != null) {
                        agendador.shutdownNow();
                        agendador = null;
                    }
                    return; // demitido: deixa de publicar
                }
                // Na MESMA secção sincronizada em que se atribui o seq: troca a
                // lista, lê o ultimoSeq/ultimoTermo e numera o heartbeat.
                lote = pendentes;
                pendentes = new ArrayList<>();
                uSeq = ultimo;
                uTermo = ultimoTermo;
                hbAtual = ++hb;
                // L e V NÃO são tocados: um heartbeat não é um evento.
            }
            if (Wire.isolated(k, i))
                return; // partição de rede: não publica (o hb continua a avançar)
            // FORA da secção sincronizada: a publicação é um pedido HTTP lento.
            Batch b = new Batch(t, i, "127.0.0.1", Wire.nodePort(k, i), hbAtual, uSeq, uTermo, lote);
            String msg = b.encode();
            ps.publish(Wire.topicData(k), msg);
            long agora = System.currentTimeMillis();
            int bytes = msg.getBytes(StandardCharsets.UTF_8).length;
            Files.writeString(Paths.get("hb-p" + k + "-n" + i + ".txt"),
                    t + ";" + hbAtual + ";" + agora + ";" + uSeq + ";" + uTermo + ";" + lote.size() + ";" + bytes + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            falar("HEARTBEAT: termo=" + t + " hb=" + hbAtual + " n=" + lote.size() + " ultimoSeq=" + uSeq
                    + " (termo " + uTermo + ")");
        } catch (Throwable e) {
            // Uma exceção que escapasse cancelaria TODAS as execuções seguintes do
            // agendador. Se a publicação falhar, os registos do lote NÃO são
            // republicados: estão no ficheiro, e os participantes recuperam-nos
            // por TCP através do ultimoSeq do lote seguinte.
            System.out.println("FALHA A PUBLICAR LOTE: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // 2 s sem leituras depois de ter registado algumas = fim da rajada -> resumo
    static void resumos() {
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (LOCK) {
                if (contados > 0 && System.currentTimeMillis() - ultimoMs >= 2000) {
                    long dur = Math.max(1, ultimoMs - primeiroMs);
                    System.out.println("--- RESUMO n" + i + " p" + k + " --- registos=" + contados
                            + " | 1.º=" + primeiroMs + " ms | último=" + ultimoMs + " ms | duração=" + dur
                            + " ms | débito=" + (contados * 1000L / dur) + " registos/s | rejeitados=" + rejeitados
                            + " | último seq=" + seq + " (termo " + ultimoTermo + ")");
                    contados = 0;
                    rejeitados = 0;
                }
            }
        }
    }
}
