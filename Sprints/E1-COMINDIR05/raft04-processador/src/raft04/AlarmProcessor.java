package raft04;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class AlarmProcessor {
    static final Object LOCK = new Object();
    static long Lp;
    static VectorClock Vp;
    static int N;
    static Partitioner partitioner;
    static IpfsPubSub ps;
    static HeartbeatMonitor[] monitors;
    static Set<String>[] recebidos;

    static void logEvent(String line) {
        String out = System.currentTimeMillis() + ";" + line;
        System.out.println(out);
        try (FileWriter fw = new FileWriter("monitor-processador.txt", true)) { fw.write(out + "\n"); } catch (IOException ignored) {}
    }

    static void onLote(int k, String msg) {
        String[] lines = msg.trim().split("\n");
        String[] h = lines[0].trim().split(";");
        int n = Integer.parseInt(h[8]);
        Batch b = Batch.decode(msg, n);
        if (monitors[k] != null) {
            monitors[k].onHeartbeat(b.term, b.leader, b.hb);
            logEvent("RECEBIDO;p" + k + ";termo=" + b.term + ";hb=" + b.hb + ";ultimoSeq=" + b.lastSeq + ";n=" + b.records.size());
        }
        for (SensorRecord r : b.records) {
            String sensor = r.getSensorId();
            if (sensor.startsWith("ALARM-")) continue;
            String key = r.getSeq() + ";" + r.getTerm();
            synchronized (LOCK) {
                if (recebidos[k].contains(key)) { System.out.println("DUPLICADO: p" + k + " seq " + r.getSeq()); continue; }
                recebidos[k].add(key);
                Lp = Math.max(Lp, r.getLamport());
                Vp.merge(r.getVector());
            }
            if (r.getTemperature() < Wire.LIMIAR_ALARME) continue;
            int j = partitioner.of("ALARM-" + sensor);
            if (monitors[j] != null && monitors[j].state() == HeartbeatMonitor.State.SUSPEITO) {
                logEvent("AVISO: partição p" + j + " sem líder conhecido; alarme " + sensor + " pode perder-se");
            }
            try {
                ps.publish(Wire.topicIn(j), Wire.in("ALARM-" + sensor, r.getTemperature(), Lp, Vp.copy()));
                System.out.println("ALARME: " + sensor + " p" + k + "#" + r.getSeq() + " -> p" + j);
            } catch (Exception e) { System.out.println("Falha a publicar alarme: " + e.getMessage()); }
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) { System.out.println("Uso: AlarmProcessor <N> [api]"); return; }
        N = Integer.parseInt(args[0]);
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        partitioner = new Partitioner(N);
        ps = new IpfsPubSub(api);
        Lp = 0; Vp = new VectorClock(N);
        monitors = new HeartbeatMonitor[N];
        recebidos = new HashSet[N];
        for (int k=0;k<N;k++){ recebidos[k]=new HashSet<>(); final int kk=k; monitors[k]=new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, msg->logEvent(msg)); }
        Thread checker = new Thread(() -> { while(true){ for(int i=0;i<N;i++) if(monitors[i]!=null) monitors[i].check(); try{Thread.sleep(Wire.VERIFICACAO_MS);}catch(InterruptedException e){break;} } });
        checker.setDaemon(true); checker.start();
        for (int k=0;k<N;k++){ final int kk=k; new Thread(() -> { try { ps.subscribe(Wire.topicData(kk), msg -> onLote(kk, msg)); } catch(Exception e){} }, "alarme-p"+kk).start(); }
    }
}
