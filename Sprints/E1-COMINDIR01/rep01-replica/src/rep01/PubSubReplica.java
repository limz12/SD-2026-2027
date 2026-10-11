package rep01;

import java.io.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        int port = 5001;
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.out.println("Porto inválido: " + args[1] + " (uso: <id> <porto>)");
                return;
            }
        }
        String api = "http://127.0.0.1:" + port + "/api/v0";
        IpfsPubSub pubsub = new IpfsPubSub(api);
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        try {
            AtomicLong ultimo = new AtomicLong(file.lastSeq());
            ConcurrentSkipListMap<Long, SensorRecord> retidos = new ConcurrentSkipListMap<>();
            AtomicLong recebidos = new AtomicLong(0);
            AtomicLong escritos = new AtomicLong(0);
            AtomicLong primeira = new AtomicLong(0);
            AtomicLong ultima = new AtomicLong(0);
            System.out.println("Réplica " + id + " à escuta no tópico " + TOPIC + " via " + api);
            pubsub.subscribe(TOPIC, line -> {
                long n = recebidos.incrementAndGet();
                long agora = System.currentTimeMillis();
                if (n == 1) {
                    primeira.set(agora);
                }
                ultima.set(agora);
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line);
                    if (n % 200 == 0) {
                        System.out.println("Resumo: recebidos=" + n + " escritos=" + escritos.get() + " retidos=" + retidos.size() + " primeira=" + primeira.get() + " ultima=" + ultima.get());
                    }
                    return;
                }
                long seq = r.getSeq();
                try {
                    if (seq == ultimo.get() + 1) {
                        file.append(r);
                        ultimo.set(seq);
                        escritos.incrementAndGet();
                        while (retidos.containsKey(ultimo.get() + 1)) {
                            SensorRecord seguinte = retidos.remove(ultimo.get() + 1);
                            file.append(seguinte);
                            ultimo.incrementAndGet();
                            escritos.incrementAndGet();
                        }
                    } else if (seq <= ultimo.get() || retidos.containsKey(seq)) {
                        System.out.println("DUPLICADO: " + seq);
                    } else {
                        retidos.put(seq, r);
                        System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo.get() + 1) + ")");
                    }
                } catch (IOException e) {
                    System.out.println("IO: " + e.getMessage());
                }
                if (n % 200 == 0) {
                    System.out.println("Resumo: recebidos=" + n + " escritos=" + escritos.get() + " retidos=" + retidos.size() + " primeira=" + primeira.get() + " ultima=" + ultima.get());
                }
            });
        } catch (IOException | InterruptedException e) {
            System.out.println("IO: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
