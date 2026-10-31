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

    static class PartState {
        final int k;
        final RecordFile file;
        long ultimo;
        final TreeMap<Long, SensorRecord> retidos = new TreeMap<>();
        String hostLider;
        int portoLider = -1;

        // COMINDIR04: monitor de batimentos do líder p<k>
        HeartbeatMonitor monitor;

        PartState(int k, RecordFile file, long ultimo) {
            this.k = k;
            this.file = file;
            this.ultimo = ultimo;
        }
    }

    static final int N = 3; // Número total de partições definido na ficha.
    static String replicaId;
    static IpfsPubSub ps;
    static Path monitorFile;

    // simulacao de perda apenas na rececao por PubSub
    static double perda = 0.0;        // -Dperda
    static long descartar = -1;       // -Ddescartar
    static final Set<Long> descartados = new HashSet<>();
    static final Random perdaRnd = new Random();

    /**
     * Escreve um evento no monitor-replica<id>.txt com o instante
     * físico atual como prefixo, e imprime-o na consola.
     *
     * Formato da linha: instante;EVENTO;p<k>;detalhes...
     */
    static synchronized void logEvento(String evento) {
        System.out.println(evento);
        try {
            Files.writeString(
                monitorFile,
                System.currentTimeMillis() + ";" + evento
                    + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.out.println(
                "Falha a registar evento no monitor: " + e.getMessage()
            );
        }
    }

    // mesma regra para PubSub e TCP — escreve / duplicado / retem.
    static void escreverEmSequencia(PartState st) throws IOException {
        while (!st.retidos.isEmpty() && st.retidos.firstKey() == st.ultimo + 1) {
            SensorRecord x = st.retidos.remove(st.ultimo + 1);
            st.file.append(x);
            st.ultimo++;
            System.out.println("Aplicado p" + st.k + " (estava retido): " + x.toLine());
            logEvento("APLICADO;p" + st.k + ";seq=" + x.getSeq() + ";registo=" + x.getTimestamp());
        }
    }

    /**
     * Aplica um registo na réplica.
     *
     * Devolve true quando o registo foi escrito em sequência
     * (ou recuperou registos retidos); false quando é duplicado
     * ou fica retido à espera de um anterior.
     */
    static boolean aplicar(PartState st, SensorRecord r) throws IOException {
        long seq = r.getSeq();
        if (seq <= st.ultimo || st.retidos.containsKey(seq)) {
            System.out.println("DUPLICADO p" + st.k + ": " + seq);
            return false;
        }
        if (seq == st.ultimo + 1) {
            st.file.append(r);
            st.ultimo = seq;
            System.out.println("Aplicado p" + st.k + ": " + r.toLine() + " (lider " + st.hostLider + ":" + st.portoLider + ")");
            logEvento("APLICADO;p" + st.k + ";seq=" + seq + ";registo=" + r.getTimestamp());
            escreverEmSequencia(st);
            return true;
        } else {
            st.retidos.put(seq, r);
            System.out.println("EM ESPERA p" + st.k + ": " + seq + " (falta o registo " + (st.ultimo + 1) + ")");
            return false;
        }
    }

    // gatilho — so quando retidos > LIMITE_RETIDOS. Corre na mesma thread
    // da subscricao (bloqueia connect/readLine no maximo T_TCP_MS).
    static void recuperar(PartState st) {
        if (st.retidos.isEmpty()) {
            return;
        }
        if (st.hostLider == null || st.portoLider < 0) {
            System.out.println("RECUPERAÇÃO p" + st.k + " FALHOU: lider desconhecido");
            return;
        }
        long de = st.ultimo + 1;
        long ate = st.retidos.firstKey() - 1;
        if (de > ate) {
            return;
        }
        logEvento("RECUPERACAO;p" + st.k + ";de=" + de + ";ate=" + ate);
        recuperarIntervalo(st, de, ate);
    }

    /**
     * Pede por TCP os registos de <de> a <ate> ao líder.
     * O intervalo é exatamente o indicado; a aplicação
     * dos registos rejeita duplicados.
     */
    static void recuperarIntervalo(PartState st, long de, long ate) {
        if (st.hostLider == null || st.portoLider < 0) {
            System.out.println("RECUPERAÇÃO p" + st.k + " FALHOU: lider desconhecido");
            return;
        }
        System.out.println("RECUPERAÇÃO p" + st.k + ": " + de + ".." + ate + " pedida a " + st.hostLider + ":" + st.portoLider);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(st.hostLider, st.portoLider), Wire.T_TCP_MS);
            s.setSoTimeout(Wire.T_TCP_MS);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("PEDIDO " + de + " " + ate);
            String linha;
            while ((linha = in.readLine()) != null) {
                String t = linha.trim();
                if (t.equals("FIM")) {
                    escreverEmSequencia(st);
                    return;
                }
                if (t.startsWith("ERRO")) {
                    throw new IOException("lider respondeu: " + t);
                }
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(t, N);
                } catch (IllegalArgumentException e) {
                    throw new IOException("resposta mal formada: " + t);
                }
                boolean eraNovo = r.getSeq() > st.ultimo && !st.retidos.containsKey(r.getSeq());
                aplicar(st, r);
                if (eraNovo) {
                    System.out.println("RECUPERADO p" + st.k + ": " + r.getSeq());
                }
            }
            throw new IOException("ligacao fechada antes de FIM");
        } catch (IOException e) {
            System.out.println("RECUPERAÇÃO p" + st.k + " FALHOU: " + e.getMessage());
        }
    }

    /**
     * Processa um LOTE recebido do líder p<k>.
     */
    static void onLote(PartState st, String msg) {
        Batch batch;
        try {
            batch = Batch.decode(msg, N);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO p" + st.k + ": " + e.getMessage());
            return;
        }

        // Atualizar o monitor de batimentos
        st.monitor.onHeartbeat(batch.getInc(), batch.getHb());

        String seqs;
        if (batch.getRecords().isEmpty()) {
            seqs = "vazio";
        } else {
            long primeiro = batch.getRecords().get(0).getSeq();
            long ultimo = batch.getRecords().get(batch.getRecords().size() - 1).getSeq();
            seqs = primeiro + ".." + ultimo;
        }
        logEvento("RECEBIDO;p" + st.k + ";inc=" + batch.getInc()
                + ";hb=" + batch.getHb() + ";ultimoSeq=" + batch.getLastSeq()
                + ";n=" + batch.size() + ";seqs=" + seqs);

        st.hostLider = batch.getAddress();
        st.portoLider = batch.getPort();

        for (SensorRecord r : batch.getRecords()) {
            long seq = r.getSeq();

            // 5d: simulacao de perda apenas na rececao por PubSub
            if (descartar >= 0 && seq == descartar && !descartados.contains(seq)) {
                descartados.add(seq);
                System.out.println("DESCARTADO p" + st.k + " (simulacao, primeira vez): " + seq);
                continue;
            }
            if (perda > 0.0 && perdaRnd.nextDouble() < perda) {
                System.out.println("DESCARTADO p" + st.k + " (simulacao, perda): " + seq);
                continue;
            }

            try {
                aplicar(st, r);
            } catch (IOException e) {
                System.out.println("Falha a escrever p" + st.k + ": " + e.getMessage());
                return;
            }
        }

        if (st.retidos.size() > Wire.LIMITE_RETIDOS) {
            recuperar(st);
            return;
        }

        // Recuperar por TCP os registos que faltam
        // até ao limite anunciado pelo lote
        if (st.ultimo < batch.getLastSeq()) {
            long de = st.ultimo + 1;
            long ate = batch.getLastSeq();
            logEvento("RECUPERACAO;p" + st.k + ";de=" + de + ";ate=" + ate);
            recuperarIntervalo(st, de, ate);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionReplica <id> <k1,k2> [portoApi|api]");
            return;
        }
        replicaId = args[0];
        String[] ks = args[1].split(",");
        String api = "http://127.0.0.1:5001/api/v0";
        if (args.length > 2) {
            api = args[2].contains("http") ? args[2] : "http://127.0.0.1:" + args[2] + "/api/v0";
        }
        String perdaProp = System.getProperty("perda");
        perda = perdaProp != null ? Double.parseDouble(perdaProp) : 0.0;
        String descartarProp = System.getProperty("descartar");
        descartar = descartarProp != null ? Long.parseLong(descartarProp) : -1;
        monitorFile = Paths.get("monitor-replica" + replicaId + ".txt");
        ps = new IpfsPubSub(api);

        List<PartState> estados = new ArrayList<>();
        for (String sk : ks) {
            int k = Integer.parseInt(sk.trim());
            RecordFile f = new RecordFile("replica-" + replicaId + "-p" + k + ".txt", N);
            long ultimo = f.lastSeq();
            PartState st = new PartState(k, f, ultimo);

            // COMINDIR04: monitor de batimentos do líder p<k>.
            // Os eventos vão para a consola e para monitor-replica<id>.txt.
            st.monitor = new HeartbeatMonitor(
                k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT,
                PartitionReplica::logEvento
            );

            estados.add(st);
            System.out.println("Replica " + replicaId + " subscreve " + Wire.topicData(k) + " (lastSeq() = " + ultimo + ")");
        }

        // COMINDIR04: thread de verificação que verifica
        // periodicamente o silêncio de todos os líderes monitorizados
        Thread verificacao = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Wire.VERIFICACAO_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (PartState st : estados) {
                    st.monitor.check();
                }
            }
        }, "verificacao");
        verificacao.setDaemon(true);
        verificacao.start();

        List<Thread> threads = new ArrayList<>();
        for (PartState st : estados) {
            Thread t = new Thread(() -> {
                try {
                    ps.subscribe(Wire.topicData(st.k), msg -> onLote(st, msg));
                } catch (IOException | InterruptedException e) {
                    System.out.println("Subscricao p" + st.k + " terminada: " + e.getMessage());
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "sub-p" + st.k);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }
    }
}
