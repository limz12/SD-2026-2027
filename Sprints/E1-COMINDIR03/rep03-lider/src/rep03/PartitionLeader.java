package rep03;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
        String corpo = Wire.body(msg);
        String[] f = corpo.split(";", -1);
        if (f.length != 4) {
            System.out.println("REJEITADO: " + msg.trim());
            return;
        }
        String sensor = f[0].trim();
        double temp;
        long lMsg;
        VectorClock vMsg;
        try {
            temp = Double.parseDouble(f[1].trim());
            lMsg = Long.parseLong(f[2].trim());
            vMsg = VectorClock.parse(f[3].trim(), n);
        } catch (Exception e) {
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
        long seq;
        synchronized (LOCK) {
            lamport = Math.max(lamport, lMsg) + 1;
            vector.merge(vMsg);
            vector.tick(k);
            ultimoSeq++;
            seq = ultimoSeq;
            if (vector.get(k) != seq) {
                throw new IllegalStateException("V[" + k + "]=" + vector.get(k) + " != seq=" + seq);
            }
            r = new SensorRecord(seq, sensor, temp, LocalDateTime.now().withNano(0).toString(), lamport, vector.copy());
            try {
                file.append(r);
            } catch (IOException e) {
                ultimoSeq--;
                vector = vector; // noop
                System.out.println("Falha a persistir: " + e.getMessage());
                return;
            }
            System.out.println("EVENTO: seq=" + seq + " L=" + lamport + " V=" + vector);
        }
        try {
            ps.publish(Wire.topicData(k), Wire.reg(hostAnunciado, porto, r));
            System.out.println("Registado e publicado: " + r.toLine());
        } catch (IOException | InterruptedException e) {
            System.out.println("Falha a publicar: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void atender(Socket s) {
        String remoto = s.getRemoteSocketAddress().toString();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String pedido = in.readLine();
            if (pedido == null) {
                return;
            }
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
                    recs = (de > topo) ? List.of() : file.range(de, topo);
                } catch (IOException e) {
                    out.println("ERRO leitura do ficheiro");
                    return;
                }
            }
            for (SensorRecord r : recs) {
                out.println(r.toLine());
            }
            out.println("FIM");
            System.out.println("RECUPERAÇÃO: " + de + ".." + topo + " para " + remoto + " (" + recs.size() + " registos)");
        } catch (IOException e) {
            System.out.println("Falha no atendimento a " + remoto + ": " + e.getMessage());
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereco=127.0.0.1] [api]");
            return;
        }
        k = Integer.parseInt(args[0]);
        n = Integer.parseInt(args[1]);
        if (k < 0 || k >= n) {
            System.out.println("k tem de satisfazer 0 <= k < N");
            return;
        }
        hostAnunciado = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";
        porto = Wire.PORTO_BASE + k;
        partitioner = new Partitioner(n);
        file = new RecordFile("lider-p" + k + ".txt");
        synchronized (LOCK) {
            SensorRecord last = file.last();
            if (last == null) {
                ultimoSeq = 0;
                lamport = 0;
                vector = new VectorClock(n);
            } else {
                ultimoSeq = last.getSeq();
                lamport = last.getLamport();
                vector = last.getVector().copy();
                if (vector == null) {
                    vector = new VectorClock(n);
                }
            }
        }
        ps = new IpfsPubSub(api);

        Thread entrada = new Thread(() -> {
            try {
                ps.subscribe(Wire.topicIn(k), PartitionLeader::onIn);
            } catch (IOException | InterruptedException e) {
                System.out.println("Subscricao terminada: " + e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "entrada-p" + k);
        entrada.start();

        System.out.println("Lider p" + k + ": lastSeq() = " + ultimoSeq + ", L=" + lamport + ", V=" + vector + ", subscreve " + Wire.topicIn(k) + ", a escuta em " + hostAnunciado + ":" + porto);
        try (ServerSocket ss = new ServerSocket(porto)) {
            while (true) {
                Socket s = ss.accept();
                atender(s);
            }
        }
    }
}
