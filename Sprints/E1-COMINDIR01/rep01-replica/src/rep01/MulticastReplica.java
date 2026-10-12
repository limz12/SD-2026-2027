package rep01;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.Timer;
import java.util.TimerTask;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;
    static final long SILENCIO_MS = 5000;

    static final class EstadoRajada {
        long primeiro, ultimo, ultimoSim, recebidos, escritos, retidos;
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        TreeMap<Long, SensorRecord> retido = new TreeMap<>();
        final EstadoRajada es = new EstadoRajada();
        long last;
        try {
            last = file.lastSeq();
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
            return;
        }
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group);
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT
                    + " (last=" + last + ")");
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
            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                socket.receive(p);
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line);
                    continue;
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
                if (seq <= last) {
                    System.out.println("DUPLICADO: " + seq);
                    continue;
                }
                if (retido.containsKey(seq)) {
                    System.out.println("DUPLICADO: " + seq);
                    continue;
                }
                retido.put(seq, r);
                if (r.getSensorId().equals("SIM")) {
                    synchronized (es) {
                        es.retidos++;
                    }
                }
                if (seq > last + 1) {
                    System.out.println("EM ESPERA: " + seq + " (falta o registo " + (last + 1) + ")");
                }
                while (retido.containsKey(last + 1)) {
                    SensorRecord proximo = retido.remove(last + 1);
                    file.append(proximo);
                    if (proximo.getSensorId().equals("SIM")) {
                        synchronized (es) {
                            es.escritos++;
                            es.retidos--;
                        }
                    }
                    last = proximo.getSeq();
                    System.out.println("Aplicado: " + proximo.toLine());
                }
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
