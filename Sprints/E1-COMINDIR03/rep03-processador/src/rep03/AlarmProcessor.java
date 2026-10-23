package rep03;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AlarmProcessor {

    static final Object LOCK = new Object();
    static long Lp = 0;
    static VectorClock Vp;
    static final Set<String> vistos = new HashSet<>();

    static IpfsPubSub ps;
    static Partitioner partitioner;
    static int n;

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
        Vp = new VectorClock(n);
        ps = new IpfsPubSub(api);

        List<Thread> threads = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            final int kk = k;
            Thread t = new Thread(() -> runPartition(kk), "alarm-p" + k);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        System.out.println("AlarmProcessor pronto (N=" + n + ", API " + api + ").");
        for (Thread t : threads) {
            t.join();
        }
    }

    static void runPartition(int k) {
        try {
            ps.subscribe(Wire.topicData(k), msg -> onReg(k, msg));
        } catch (IOException | InterruptedException e) {
            System.out.println("Subscricao terminada p" + k + ": " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void onReg(int k, String msg) {
        String[] parts;
        try {
            parts = Wire.regParts(msg);
        } catch (IllegalArgumentException e) {
            return;
        }
        SensorRecord r;
        try {
            r = SensorRecord.fromLine(parts[2]);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (r.getSensorId().startsWith("ALARM-")) {
            return;
        }

        boolean alarm = false;
        String sensor = r.getSensorId();
        double temp = r.getTemperature();
        int j = -1;
        long lPub = 0;
        VectorClock vPub = null;

        synchronized (LOCK) {
            String key = k + "#" + r.getSeq();
            if (vistos.contains(key)) {
                return;
            }
            vistos.add(key);

            Lp = Math.max(Lp, r.getLamport());
            Vp.merge(r.getVector());

            if (temp >= Wire.LIMIAR_ALARME) {
                alarm = true;
                lPub = Lp;
                vPub = Vp.copy();
                j = partitioner.of("ALARM-" + sensor);
            }
        }

        if (alarm) {
            try {
                ps.publish(Wire.topicIn(j), Wire.in("ALARM-" + sensor, temp, lPub, vPub));
                System.out.println("ALARME: " + sensor + " p" + k + "#" + r.getSeq() + " -> p" + j);
            } catch (IOException | InterruptedException e) {
                System.out.println("Falha a publicar alarme: " + e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
