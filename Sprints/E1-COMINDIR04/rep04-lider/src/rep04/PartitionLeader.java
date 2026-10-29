package rep04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
public class PartitionLeader {

    static final Object LOCK = new Object();

    static long ultimoSeq;
    static long lamport;
    static VectorClock vector;

    static long inc;
    static long hb;

    static List<SensorRecord> pendentes = new ArrayList<>();
    static ScheduledExecutorService scheduler;
    static RecordFile file;

    static int k;
    static int N;
    static Partitioner partitioner;

    static String hostAnunciado;
    static int porto;
    static IpfsPubSub ps;

    /**
     * Processa uma mensagem IN recebida do produtor.
     *
     * Formato:
     * IN;sensor;temperatura;L;V
     */
    static void onIn(String msg) {

        String tipo;

        try {
            tipo = Wire.type(msg);

        } catch (IllegalArgumentException e) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        if (!tipo.equals("IN")) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        String corpo = Wire.body(msg);
        String[] f = corpo.split(";", -1);

        // Exigir quatro campos no corpo
        if (f.length != 4) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        String sensor = f[0].trim();

        double temp;
        long lamportRecebido;
        VectorClock vectorRecebido;

        try {

            // Campo 1: temperatura
            temp = Double.parseDouble(f[1].trim());

            // Campo 2: relógio de Lamport recebido
            lamportRecebido = Long.parseLong(f[2].trim());

            // Campo 3: relógio vetorial recebido
            vectorRecebido = VectorClock.parse(
                f[3].trim(), N
            );

        } catch (IllegalArgumentException e) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        if (sensor.isEmpty()
                || !Double.isFinite(temp)
                || lamportRecebido < 0) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        // Verificar se o sensor pertence a esta partição
        int destino = partitioner.of(sensor);

        if (destino != k) {

            System.out.println(
                "PARTIÇÃO ERRADA: " + sensor
                + " é de p" + destino
                + ", aqui é p" + k
            );
            return;
        }

        SensorRecord r;

        // SECÇÃO SINCRONIZADA:
        // atualizar relógios, atribuir sequência,
        // verificar consistência e persistir
        synchronized (LOCK) {

            try {

                // 1. Atualização do relógio de Lamport
                long novoLamport = Math.addExact(
                    Math.max(lamport, lamportRecebido),
                    1
                );

                // 2. Preparar uma cópia do vetor local
                VectorClock novoVector = vector.copy();

                // Incorporar o vetor recebido
                novoVector.merge(vectorRecebido);

                // Contabilizar o evento local
                novoVector.tick(k);

                // 3. Atribuir a próxima sequência
                long novoSeq = Math.addExact(
                    ultimoSeq, 1
                );

                // 4. Verificar V[k] == seq
                if (novoVector.get(k) != novoSeq) {

                    System.out.println(
                        "ERRO: V[" + k + "] = "
                        + novoVector.get(k)
                        + " diferente de seq = " + novoSeq
                    );

                    return;
                }

                // 5. Criar o registo com os relógios
                r = SensorRecord.now(
                    novoSeq,
                    sensor,
                    temp,
                    novoLamport,
                    novoVector
                );

                // 6. Persistir o registo
                file.append(r);

                // 7. Confirmar o estado apenas após
                // a escrita bem-sucedida
                ultimoSeq = novoSeq;
                lamport = novoLamport;
                vector = novoVector;

                pendentes.add(r);

                System.out.println(
                    "EVENTO: seq=" + ultimoSeq
                    + " L=" + lamport
                    + " V=" + vector
                );
            } catch (IOException e) {

                System.out.println(
                    "Falha a persistir: "
                    + e.getMessage()
                );
                return;

            } catch (ArithmeticException e) {

                System.out.println(
                    "Erro ao atualizar relógios: "
                    + e.getMessage()
                );
                return;
            }
        }

        // Não publica REG por registo: acumulado em pendentes,
        // publicado em lote por enviarLote()
    }
    /**
     * Atende um pedido de recuperação TCP.
     */
    static void atender(Socket s) {

        String remoto =
            s.getRemoteSocketAddress().toString();

        try (
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    s.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );

            PrintWriter out = new PrintWriter(
                s.getOutputStream(),
                true,
                StandardCharsets.UTF_8
            )
        ) {

            // Tempo máximo de espera por cada linha
            s.setSoTimeout(Wire.T_TCP_MS);

            String pedido = in.readLine();

            if (pedido == null) {
                return;
            }

            String[] p = pedido.trim().split("\\s+");

            long de;
            long ate;

            if (p.length != 3
                    || !p[0].equals("PEDIDO")) {

                out.println("ERRO pedido mal formado");
                return;
            }

            try {

                de = Long.parseLong(p[1]);
                ate = Long.parseLong(p[2]);

            } catch (NumberFormatException e) {

                out.println("ERRO pedido mal formado");
                return;
            }

            if (de <= 0 || ate <= 0 || de > ate) {

                out.println("ERRO intervalo inválido");
                return;
            }

            List<SensorRecord> recs;
            long topo;

            // Consultar sequência e obter registos
            // dentro da secção sincronizada
            synchronized (LOCK) {

                topo = Math.min(ate, ultimoSeq);

                try {

                    recs = (de > topo)
                            ? List.of()
                            : file.range(de, topo);

                } catch (IOException e) {

                    out.println(
                        "ERRO leitura do ficheiro"
                    );
                    return;
                }
            }

            // Enviar os registos fora do bloqueio
            for (SensorRecord r : recs) {

                out.println(r.toLine());
            }

            out.println("FIM");

            System.out.println(
                "RECUPERAÇÃO: " + de + ".." + topo
                + " para " + remoto
                + " (" + recs.size() + " registos)"
            );

        } catch (IOException e) {

            System.out.println(
                "Falha no atendimento a " + remoto
                + ": " + e.getMessage()
            );

        } finally {

            try {
                s.close();

            } catch (IOException ignored) {
            }
        }
    }

    static void enviarLote() {
        List<SensorRecord> lote;
        long seqLote;
        long hbLote;
        synchronized (LOCK) {
            lote = pendentes;
            pendentes = new ArrayList<>();
            seqLote = ultimoSeq;
            hb = Math.addExact(hb, 1);
            hbLote = hb;
        }
        Batch b = new Batch(hostAnunciado, porto, inc, hbLote, seqLote, lote);
        String msg = b.encode();
        long instant = System.currentTimeMillis();
        try {
            ps.publish(Wire.topicData(k), msg);
            System.out.println("HEARTBEAT: hb=" + hbLote + " n=" + lote.size() + " ultimoSeq=" + seqLote);
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter("hb-p" + k + ".txt", true))) {
                pw.println(inc + ";" + hbLote + ";" + instant + ";" + seqLote + ";" + lote.size() + ";" + msg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("Falha a publicar lote: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /**
     * Arranque do líder da partição.
     *
     * Argumentos:
     * PartitionLeader <k> <N> [endereco] [api]
     */
    public static void main(String[] args)
            throws Exception {

        if (args.length < 2 || args.length > 4) {

            System.out.println(
                "Uso: PartitionLeader <k> <N> "
                + "[endereco=127.0.0.1] [api]"
            );
            return;
        }

        try {

            k = Integer.parseInt(args[0]);
            N = Integer.parseInt(args[1]);

        } catch (NumberFormatException e) {

            System.out.println(
                "Erro: k e N devem ser inteiros."
            );
            return;
        }

        if (N <= 0 || k < 0 || k >= N) {

            System.out.println(
                "k tem de satisfazer 0 <= k < N, com N > 0"
            );
            return;
        }

        hostAnunciado = args.length > 2
                ? args[2]
                : "127.0.0.1";

        String api = args.length > 3
                ? args[3]
                : "http://127.0.0.1:5001/api/v0";

        porto = Wire.PORTO_BASE + k;

        partitioner = new Partitioner(N);

        file = new RecordFile(
            "lider-p" + k + ".txt", N
        );

        inc = System.currentTimeMillis();
        hb = 0;

        // Inicializar sequência e relógios
        // a partir do último registo persistido
        synchronized (LOCK) {

            SensorRecord ultimo = file.last();

            if (ultimo == null) {

                // Ficheiro vazio
                ultimoSeq = 0;
                lamport = 0;
                vector = new VectorClock(N);

            } else {

                // Recuperar o estado do último registo
                ultimoSeq = ultimo.getSeq();
                lamport = ultimo.getLamport();
                vector = ultimo.getVector();

                // Verificar a consistência inicial
                if (vector.get(k) != ultimoSeq) {

                    throw new IllegalStateException(
                        "Estado inicial inconsistente: "
                        + "V[" + k + "] = "
                        + vector.get(k)
                        + ", seq = " + ultimoSeq
                    );
                }
            }
        }

        ps = new IpfsPubSub(api);

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(PartitionLeader::enviarLote, 0, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);

        // Thread de receção das mensagens IN
        Thread entrada = new Thread(() -> {

            try {

                ps.subscribe(
                    Wire.topicIn(k),
                    PartitionLeader::onIn
                );

            } catch (IOException | InterruptedException e) {

                System.out.println(
                    "Subscrição terminada: "
                    + e.getMessage()
                );

                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

        }, "entrada-p" + k);

        // Criar primeiro o ServerSocket.
        // Só iniciar a thread se o porto estiver livre.
        try (ServerSocket ss = new ServerSocket(porto)) {

            System.out.println(
                "Líder p" + k
                + ": seq inicial = " + ultimoSeq
                + ", L = " + lamport
                + ", V = " + vector
            );

            System.out.println(
                "Tópico de entrada: "
                + Wire.topicIn(k)
            );

            System.out.println(
                "Tópico de dados: "
                + Wire.topicData(k)
            );

            System.out.println(
                "Servidor de recuperação à escuta em "
                + hostAnunciado + ":" + porto
            );

            entrada.start();

            // Atendimento sequencial das recuperações
            while (true) {

                Socket s = ss.accept();
                atender(s);
            }
        }
    }
}
