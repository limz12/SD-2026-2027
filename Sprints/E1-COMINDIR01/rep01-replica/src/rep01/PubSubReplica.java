package rep01;

import java.io.IOException;
import java.util.TreeMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

public class PubSubReplica {
    static final long SILENCIO_MS = 5000;

    static final class EstadoRajada {
        long primeiro, ultimo, ultimoSim, recebidos, escritos, retidos;
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        int portoApi = args.length > 1 ? Integer.parseInt(args[1]) : 5001;
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        TreeMap<Long, SensorRecord> retido = new TreeMap<>();
        final EstadoRajada es = new EstadoRajada();
        AtomicLong last;
        try {
            last = new AtomicLong(file.lastSeq());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
            return;
        }
        String api = "http://127.0.0.1:" + portoApi + "/api/v0";
        System.out.println("Réplica " + id + " no tópico rep01-temperaturas (last=" + last.get()
                + ", api=" + api + ")");
        new Timer(true).scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (es.recebidos > 0 && System.currentTimeMillis() - es.ultimoSim > SILENCIO_MS) {
                    synchronized (es) {
                        if (es.recebidos > 0 && System.currentTimeMillis() - es.ultimoSim > SILENCIO_MS) {
                            System.out.println("FIM DA RAJADA: primeiro=" + es.primeiro + " ultimo=" + es.ultimo
                                    + " recebidos=" + es.recebidos + " escritos=" + es.escritos
                                    + " retidos=" + es.retidos);
                            es.recebidos = 0; es.escritos = 0; es.retidos = 0;
                            es.primeiro = 0; es.ultimo = 0; es.ultimoSim = 0;
                        }
                    }
                }
            }
        }, SILENCIO_MS, 1000);
        try {
            new IpfsPubSub(api).subscribe("rep01-temperaturas", line -> {
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line);
                    return;
                }
                long seq = r.getSeq();
                if (r.getSensorId().equals("SIM")) {
                    synchronized (es) {
                        long agora = System.currentTimeMillis();
                        if (es.recebidos == 0) {
                            es.primeiro = agora;
                        }
                        es.ultimo = agora;
                        es.ultimoSim = agora;
                        es.recebidos++;
                    }
                }
                if (seq <= last.get() || retido.containsKey(seq)) {
                    System.out.println("DUPLICADO: " + seq);
                    return;
                }
                retido.put(seq, r);
                if (r.getSensorId().equals("SIM")) {
                    synchronized (es) {
                        es.retidos++;
                    }
                }
                if (seq > last.get() + 1) {
                    System.out.println("EM ESPERA: " + seq + " (falta o registo " + (last.get() + 1) + ")");
                }
                while (retido.containsKey(last.get() + 1)) {
                    SensorRecord proximo = retido.remove(last.get() + 1);
                    try {
                        file.append(proximo);
                    } catch (IOException e) {
                        System.out.println("IO: " + e.getMessage());
                        return;
                    }
                    last.set(proximo.getSeq());
                    if (proximo.getSensorId().equals("SIM")) {
                        synchronized (es) {
                            es.escritos++;
                            es.retidos--;
                        }
                    }
                    System.out.println("Aplicado: " + proximo.toLine());
                }
            });
        } catch (IOException | InterruptedException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
