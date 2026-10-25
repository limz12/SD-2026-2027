package rep03;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AlarmProcessor {
    private static final Object LOCK = new Object();
    private static long lp;
    private static VectorClock vp;
    private static List<Set<Long>> vistos;
    private static int n;
    private static Partitioner partitioner;
    private static IpfsPubSub ps;

    static void onReg(int k, String msg) {
        SensorRecord r;
        try {
            String[] parts = Wire.regParts(msg);
            r = SensorRecord.fromLine(parts[2]);
            r = new SensorRecord(r.getSeq(), r.getSensorId(), r.getTemperature(), r.getTimestamp(),
                    r.getLamport(), VectorClock.parse(r.getVector().toString(), n));
        } catch (IllegalArgumentException e) {
            System.out.println("REG rejeitado em p" + k + ": " + msg.trim() + " (" + e.getMessage() + ")");
            return;
        }

        VectorClock snapshot;
        long lamportSnapshot;
        synchronized (LOCK) {
            if (!vistos.get(k).add(r.getSeq())) {
                System.out.println("DUPLICADO no processador p" + k + "#" + r.getSeq());
                return;
            }
            lp = Math.max(lp, r.getLamport());
            vp.merge(r.getVector());
            lamportSnapshot = lp;
            snapshot = vp.copy();
        }

        if (r.getSensorId().startsWith("ALARM-")) return;
        if (!(r.getTemperature() >= Wire.LIMIAR_ALARME)) return;

        String sensorAlarme = "ALARM-" + r.getSensorId();
        int j = partitioner.of(sensorAlarme);
        String in = Wire.in(sensorAlarme, r.getTemperature(), lamportSnapshot, snapshot);
        try {
            ps.publish(Wire.topicIn(j), in);
            System.out.println("ALARME: " + r.getSensorId() + " p" + k + "#" + r.getSeq() + " → p" + j);
        } catch (IOException | InterruptedException e) {
            System.out.println("Falha a publicar alarme de " + r.getSensorId() + ": " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: AlarmProcessor <N> [api]");
            return;
        }
        n = Integer.parseInt(args[0]);
        if (n <= 0) {
            System.out.println("N tem de ser > 0");
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        partitioner = new Partitioner(n);
        ps = new IpfsPubSub(api);
        lp = 0;
        vp = new VectorClock(n);
        vistos = new ArrayList<>();
        for (int k = 0; k < n; k++) vistos.add(new HashSet<>());

        List<Thread> threads = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            final int particao = k;
            Thread t = new Thread(() -> {
                try {
                    ps.subscribe(Wire.topicData(particao), msg -> onReg(particao, msg));
                } catch (IOException | InterruptedException e) {
                    System.out.println("Subscrição p" + particao + " terminada: " + e.getMessage());
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            }, "processador-p" + k);
            t.start();
            threads.add(t);
        }
        System.out.println("Processador de alarmes pronto (N=" + n + ", API " + api + ").");
        for (Thread t : threads) t.join();
    }
}
