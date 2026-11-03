
package rep04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

public class PartitionReplica {

    // Número total de partições definido na ficha
    static final int N = 3;

    static String replicaId;
    static IpfsPubSub ps;
    static Path ficheiroMonitor;

    static final Object LOCK_LOG = new Object();

    // Simulação de perdas apenas na receção PubSub
    static double perda = 0.0;
    static long descartar = -1;

    static final Random perdaRnd = new Random();

    /**
     * Estado de uma partição guardada pela réplica.
     */
    static class PartState {

        final int k;
        final RecordFile file;
        final HeartbeatMonitor monitor;

        long ultimo;

        final TreeMap<Long, SensorRecord> retidos =
                new TreeMap<>();

        final Set<Long> descartados = new HashSet<>();

        String hostLider;
        int portoLider = -1;

        PartState(int k, RecordFile file, long ultimo) {

            this.k = k;
            this.file = file;
            this.ultimo = ultimo;

            // Utilizar a classe HeartbeatMonitor separada.
            // A função registar escreve os eventos
            // na consola e no ficheiro da réplica.
            this.monitor = new HeartbeatMonitor(
                k,
                Wire.PERIODO_HB_MS,
                Wire.FATOR_TIMEOUT,
                PartitionReplica::registar
            );
        }
    }

    /**
     * Escreve um evento na consola e no ficheiro
     * monitor-replica<id>.txt.
     *
     * O instante é obtido com currentTimeMillis().
     *
     * A medição do silêncio entre heartbeats
     * pertence exclusivamente ao HeartbeatMonitor,
     * que utiliza System.nanoTime().
     */
    static void registar(String evento) {

        String linha =
                System.currentTimeMillis()
                + ";" + evento;

        synchronized (LOCK_LOG) {

            System.out.println(linha);

            try {

                Files.writeString(
                    ficheiroMonitor,
                    linha + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );

            } catch (IOException e) {

                System.err.println(
                    "Falha a escrever monitor: "
                    + e.getMessage()
                );
            }
        }
    }

    /**
     * Aplica os registos retidos que passaram
     * a estar em sequência.
     *
     * Deve ser chamado com o estado da
     * partição sincronizado.
     */
    static void escreverEmSequencia(PartState st)
            throws IOException {

        while (st.retidos.containsKey(st.ultimo + 1)) {

            long proximo = st.ultimo + 1;

            SensorRecord r = st.retidos.get(proximo);

            // Persistir antes de retirar dos retidos
            st.file.append(r);

            st.ultimo = proximo;
            st.retidos.remove(proximo);

            registar(
                "APLICADO;p" + st.k
                + ";seq=" + proximo
                + ";origem=retidos"
            );
        }
    }

    /**
     * Regra de aplicação da COMINDIR02:
     *
     * - Ignorar duplicados.
     * - Aplicar registos em sequência.
     * - Reter registos fora de sequência.
     */
    static void aplicar(PartState st, SensorRecord r)
            throws IOException {

        long seq = r.getSeq();

        // Registo duplicado
        if (seq <= st.ultimo
                || st.retidos.containsKey(seq)) {

            registar(
                "DUPLICADO;p" + st.k
                + ";seq=" + seq
            );
            return;
        }

        // Próximo registo esperado
        if (seq == st.ultimo + 1) {

            st.file.append(r);
            st.ultimo = seq;

            registar(
                "APLICADO;p" + st.k
                + ";seq=" + seq
            );

            // Descarregar registos retidos em sequência
            escreverEmSequencia(st);

        } else {

            // Existe uma lacuna na sequência
            st.retidos.put(seq, r);

            registar(
                "EM_ESPERA;p" + st.k
                + ";seq=" + seq
                + ";falta=" + (st.ultimo + 1)
            );
        }
    }

    /**
     * Recupera registos por TCP.
     *
     * Formato do pedido:
     * PEDIDO <de> <ate>
     *
     * Os registos recuperados não estão sujeitos
     * à simulação de perdas PubSub.
     *
     * Deve ser chamado com PartState sincronizado.
     */
    static void recuperar(
            PartState st, long de, long ate) {

        if (de > ate) {
            return;
        }

        if (st.hostLider == null
                || st.portoLider <= 0) {

            registar(
                "RECUPERACAO_FALHOU;p" + st.k
                + ";lider=desconhecido"
            );
            return;
        }

        // Evento exigido pela ficha
        registar(
            "RECUPERACAO;p" + st.k
            + ";de=" + de
            + ";ate=" + ate
        );

        try (Socket s = new Socket()) {

            s.connect(
                new InetSocketAddress(
                    st.hostLider,
                    st.portoLider
                ),
                Wire.T_TCP_MS
            );

            s.setSoTimeout(Wire.T_TCP_MS);

            PrintWriter out = new PrintWriter(
                s.getOutputStream(),
                true,
                StandardCharsets.UTF_8
            );

            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    s.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );

            out.println("PEDIDO " + de + " " + ate);

            String linha;

            while ((linha = in.readLine()) != null) {

                String t = linha.trim();

                if (t.equals("FIM")) {

                    escreverEmSequencia(st);
                    return;
                }

                if (t.startsWith("ERRO")) {

                    throw new IOException(
                        "Líder respondeu: " + t
                    );
                }

                SensorRecord r;

                try {

                    r = SensorRecord.fromLine(t, N);

                } catch (IllegalArgumentException e) {

                    throw new IOException(
                        "Registo TCP inválido: " + t,
                        e
                    );
                }

                long seq = r.getSeq();

                if (seq < de || seq > ate) {

                    throw new IOException(
                        "Registo TCP fora do intervalo: "
                        + seq
                    );
                }

                boolean novo =
                        seq > st.ultimo
                        && !st.retidos.containsKey(seq);

                aplicar(st, r);

                if (novo) {

                    registar(
                        "RECUPERADO;p" + st.k
                        + ";seq=" + seq
                    );
                }
            }

            throw new IOException(
                "Ligação fechada antes de FIM"
            );

        } catch (IOException e) {

            registar(
                "RECUPERACAO_FALHOU;p" + st.k
                + ";erro=" + e.getMessage()
            );
        }
    }

    /**
     * Processa um LOTE recebido por PubSub.
     *
     * 1. Descodifica o lote.
     * 2. Atualiza o monitor de heartbeat.
     * 3. Assinala RECEBIDO.
     * 4. Aplica os registos pela ordem.
     * 5. Recupera por TCP até lote.lastSeq.
     */
    static void onLote(PartState st, String msg) {

        Batch lote;

        try {

            lote = Batch.decode(msg, N);

        } catch (IllegalArgumentException e) {

            registar(
                "REJEITADO;p" + st.k
                + ";erro=" + e.getMessage()
            );
            return;
        }

        synchronized (st) {

            // Guardar endereço e porto do líder
            st.hostLider = lote.address;
            st.portoLider = lote.port;

            // 1. Comunicar heartbeat ao monitor separado.
            // SUSPEITA, RETOMA, REINICIO e HB_PERDIDO
            // são tratados pelo próprio monitor.
            st.monitor.onHeartbeat(
                lote.inc,
                lote.hb
            );

            // 2. Determinar o intervalo do lote
            String intervalo;

            if (lote.records.isEmpty()) {

                intervalo = "vazio";

            } else {

                long minimo = Long.MAX_VALUE;
                long maximo = Long.MIN_VALUE;

                for (SensorRecord r : lote.records) {

                    minimo = Math.min(
                        minimo, r.getSeq()
                    );

                    maximo = Math.max(
                        maximo, r.getSeq()
                    );
                }

                intervalo = minimo + ".." + maximo;
            }

            // 3. Assinalar receção do lote
            registar(
                "RECEBIDO;p" + st.k
                + ";inc=" + lote.inc
                + ";hb=" + lote.hb
                + ";ultimoSeq=" + lote.lastSeq
                + ";n=" + lote.records.size()
                + ";intervalo=" + intervalo
            );

            // 4. Aplicar registos pela ordem recebida
            for (SensorRecord r : lote.records) {

                long seq = r.getSeq();

                // Descartar uma sequência apenas uma vez
                // por partição, para simulação
                if (descartar >= 0
                        && seq == descartar
                        && !st.descartados.contains(seq)) {

                    st.descartados.add(seq);

                    registar(
                        "DESCARTADO;p" + st.k
                        + ";seq=" + seq
                        + ";motivo=simulacao"
                    );

                    continue;
                }

                // Simulação de perda apenas em PubSub
                if (perda > 0.0
                        && perdaRnd.nextDouble() < perda) {

                    registar(
                        "DESCARTADO;p" + st.k
                        + ";seq=" + seq
                        + ";motivo=perda"
                    );

                    continue;
                }

                try {

                    aplicar(st, r);

                } catch (IOException e) {

                    registar(
                        "ESCRITA_FALHOU;p" + st.k
                        + ";seq=" + seq
                        + ";erro=" + e.getMessage()
                    );

                    return;
                }
            }

            // 5. Recuperação final por TCP.
            //
            // Esta verificação é necessária mesmo
            // quando o lote está vazio (n = 0).
            if (st.ultimo < lote.lastSeq) {

                recuperar(
                    st,
                    st.ultimo + 1,
                    lote.lastSeq
                );
            }
        }
    }

    /**
     * Execução:
     * PartitionReplica <id> <k1,k2> [portoApi|api]
     */
    public static void main(String[] args)
            throws Exception {

        if (args.length < 2 || args.length > 3) {

            System.out.println(
                "Uso: PartitionReplica <id> "
                + "<k1,k2> [portoApi|api]"
            );
            return;
        }

        replicaId = args[0];

        String[] ks = args[1].split(",");

        String api =
                "http://127.0.0.1:5001/api/v0";

        if (args.length > 2) {

            api = args[2].startsWith("http")
                    ? args[2]
                    : "http://127.0.0.1:"
                        + args[2] + "/api/v0";
        }

        // Configurar simulação de perdas
        perda = Double.parseDouble(
            System.getProperty("perda", "0.0")
        );

        descartar = Long.parseLong(
            System.getProperty("descartar", "-1")
        );

        if (!Double.isFinite(perda)
                || perda < 0.0
                || perda > 1.0) {

            throw new IllegalArgumentException(
                "A perda deve estar entre 0 e 1"
            );
        }

        if (Wire.PERIODO_HB_MS <= 0
                || Wire.FATOR_TIMEOUT <= 0
                || Wire.VERIFICACAO_MS <= 0) {

            throw new IllegalArgumentException(
                "Parâmetros de heartbeat inválidos"
            );
        }

        // Inicializar ficheiro de monitorização
        // antes da criação dos monitores
        ficheiroMonitor = Paths.get(
            "monitor-replica" + replicaId + ".txt"
        );

        ps = new IpfsPubSub(api);

        List<PartState> estados = new ArrayList<>();
        Set<Integer> particoes = new HashSet<>();

        // Inicializar as partições
        for (String sk : ks) {

            int k = Integer.parseInt(sk.trim());

            if (k < 0 || k >= N) {

                throw new IllegalArgumentException(
                    "Partição inválida: " + k
                );
            }

            if (!particoes.add(k)) {

                throw new IllegalArgumentException(
                    "Partição repetida: " + k
                );
            }

            RecordFile f = new RecordFile(
                "replica-" + replicaId
                + "-p" + k + ".txt",
                N
            );

            long ultimo = f.lastSeq();

            PartState st = new PartState(
                k, f, ultimo
            );

            estados.add(st);

            registar(
                "SUBSCRICAO;p" + k
                + ";topic=" + Wire.topicData(k)
                + ";ultimo=" + ultimo
            );
        }

        List<Thread> threads = new ArrayList<>();

        // Uma thread PubSub por partição
        for (PartState st : estados) {

            Thread t = new Thread(() -> {

                try {

                    ps.subscribe(
                        Wire.topicData(st.k),
                        msg -> onLote(st, msg)
                    );

                } catch (IOException
                        | InterruptedException e) {

                    registar(
                        "SUBSCRICAO_TERMINADA;p"
                        + st.k
                        + ";erro=" + e.getMessage()
                    );

                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }

            }, "sub-p" + st.k);

            t.start();
            threads.add(t);
        }

        // Thread de verificação periódica
        // dos HeartbeatMonitor separados
        Thread verificacao = new Thread(() -> {

            while (!Thread.currentThread().isInterrupted()) {

                try {

                    Thread.sleep(Wire.VERIFICACAO_MS);

                    for (PartState st : estados) {

                        // O monitor utiliza internamente
                        // System.nanoTime() para medir silêncio.
                        st.monitor.check();
                    }

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                }
            }

        }, "verificacao-heartbeats");

        verificacao.setDaemon(true);
        verificacao.start();

        registar(
            "MONITORIZACAO_INICIADA"
            + ";periodo=" + Wire.PERIODO_HB_MS
            + ";fator=" + Wire.FATOR_TIMEOUT
            + ";verificacao=" + Wire.VERIFICACAO_MS
        );

        // Manter as subscrições ativas
        for (Thread t : threads) {
            t.join();
        }
    }
}
