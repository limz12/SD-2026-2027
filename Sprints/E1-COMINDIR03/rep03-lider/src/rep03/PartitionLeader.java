package rep03;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PartitionLeader {
    static final Object LOCK = new Object();
    static long ultimoSeq;
    static long lamport;
    static VectorClock vector;
    static RecordFile file;
    static int k;
    static int n;
    static Partitioner partitioner;
    static String hostAnunciado;
    static int porto;
    static IpfsPubSub ps;

    static void onIn(String msg) {
        String tipo;
        try {
            tipo = Wire.type(msg);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + msg.trim());
            return;
        }
        if (!tipo.equals("IN")) {
            System.out.println("REJEITADO: " + msg.trim());
            return;
        }
        String[] f = Wire.body(msg).split(";", -1);
        if (f.length != 4) {
            System.out.println("REJEITADO: " + msg.trim());
            return;
        }
        String sensor = f[0].trim();
        double temp;
        long lamportMsg;
        VectorClock vectorMsg;
        try {
            temp = Double.parseDouble(f[1].trim());
            lamportMsg = Long.parseLong(f[2].trim());
            vectorMsg = VectorClock.parse(f[3], n);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + msg.trim());
            return;
        }
        if (sensor.isEmpty()) {
            System.out.println("REJEITADO: " + msg.trim());
            return;
        }
        if (partitioner.of(sensor) != k) {
            System.out.println("PARTIÇÃO ERRADA: " + sensor + " é de p" + partitioner.of(sensor) + ", aqui é p" + k);
            return;
        }

        SensorRecord r;
        synchronized (LOCK) {
            long lamportAnterior = lamport;
            long seqAnterior = ultimoSeq;
            VectorClock vectorAnterior = vector.copy();
            lamport = Math.max(lamport, lamportMsg) + 1;
            vector.merge(vectorMsg);
            vector.tick(k);
            long seq = ++ultimoSeq;
            if (vector.get(k) != seq) {
                System.err.println("ERRO DE INVARIANTE: V[" + k + "]=" + vector.get(k) + " != seq=" + seq);
            }
            VectorClock snapshot = vector.copy();
            r = SensorRecord.now(seq, sensor, temp, lamport, snapshot);
            try {
                file.append(r);
            } catch (IOException e) {
                lamport = lamportAnterior;
                vector = vectorAnterior;
                ultimoSeq = seqAnterior;
                System.out.println("Falha a persistir: " + e.getMessage());
                return;
            }
        }
        System.out.println("EVENTO: seq=" + r.getSeq() + " L=" + r.getLamport() + " V=" + r.getVector());
        try {
            ps.publish(Wire.topicData(k), Wire.reg(hostAnunciado, porto, r));
        } catch (IOException | InterruptedException e) {
            System.out.println("Falha a publicar: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    static void atender(Socket s) {
        String remoto = s.getRemoteSocketAddress().toString();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String pedido = in.readLine();
            if (pedido == null) return;
            String[] p = pedido.trim().split("\\s+");
            long de, ate;
            if (p.length != 3 || !p[0].equals("PEDIDO")) {
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
            if (de > ate) {
                out.println("ERRO de > ate");
                return;
            }
            List<SensorRecord> recs;
            long topo;
            synchronized (LOCK) {
                topo = Math.min(ate, ultimoSeq);
                try {
                    recs = de > topo ? List.of() : file.range(de, topo);
                } catch (IOException e) {
                    out.println("ERRO leitura do ficheiro");
                    return;
                }
            }
            for (SensorRecord r : recs) out.println(r.toLine());
            out.println("FIM");
            System.out.println("RECUPERAÇÃO: " + de + ".." + topo + " para " + remoto + " (" + recs.size() + " registos)");
        } catch (IOException e) {
            System.out.println("Falha no atendimento a " + remoto + ": " + e.getMessage());
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereco=127.0.0.1] [api]");
            return;
        }
        k = Integer.parseInt(args[0]);
        n = Integer.parseInt(args[1]);
        if (n <= 0 || k < 0 || k >= n) {
            System.out.println("É necessário N > 0 e 0 <= k < N");
            return;
        }
        hostAnunciado = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";
        porto = Wire.PORTO_BASE + k;
        partitioner = new Partitioner(n);
        file = new RecordFile("lider-p" + k + ".txt");
        synchronized (LOCK) {
            SensorRecord ultimo = file.last();
            if (ultimo == null) {
                ultimoSeq = 0;
                lamport = 0;
                vector = new VectorClock(n);
            } else {
                ultimoSeq = ultimo.getSeq();
                lamport = ultimo.getLamport();
                vector = VectorClock.parse(ultimo.getVector().toString(), n);
                if (vector.get(k) != ultimoSeq) {
                    System.err.println("ERRO DE INVARIANTE NO ARRANQUE: V[" + k + "]=" + vector.get(k) + " != seq=" + ultimoSeq);
                }
            }
        }
        ps = new IpfsPubSub(api);

        Thread entrada = new Thread(() -> {
            try {
                ps.subscribe(Wire.topicIn(k), PartitionLeader::onIn);
            } catch (IOException | InterruptedException e) {
                System.out.println("Subscrição terminada: " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "entrada-p" + k);
        entrada.start();

        System.out.println("Líder p" + k + ": seq=" + ultimoSeq + " L=" + lamport + " V=" + vector
                + ", subscreve " + Wire.topicIn(k) + ", à escuta em " + hostAnunciado + ":" + porto);
        try (ServerSocket ss = new ServerSocket(porto)) {
            while (true) atender(ss.accept());
        }
    }
}
