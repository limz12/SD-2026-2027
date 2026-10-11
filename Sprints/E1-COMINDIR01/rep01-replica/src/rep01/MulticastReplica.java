package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group); // passa a receber o que é enviado ao grupo
            long ultimo = file.lastSeq();
            Map<Long, SensorRecord> retidos = new TreeMap<>();
            long recebidos = 0;
            long escritos = 0;
            long primeira = 0;
            long ultima = 0;
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT);
            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                socket.receive(p); // bloqueia até chegar um datagrama
                recebidos++;
                long agora = System.currentTimeMillis();
                if (recebidos == 1) {
                    primeira = agora;
                }
                ultima = agora;
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line);
                    if (recebidos % 200 == 0) {
                        System.out.println("Resumo: recebidos=" + recebidos + " escritos=" + escritos + " retidos=" + retidos.size() + " primeira=" + primeira + " ultima=" + ultima);
                    }
                    continue;
                }
                long seq = r.getSeq();
                try {
                    if (seq == ultimo + 1) {
                        file.append(r);
                        ultimo = seq;
                        escritos++;
                        while (retidos.containsKey(ultimo + 1)) {
                            SensorRecord seguinte = retidos.remove(ultimo + 1);
                            file.append(seguinte);
                            ultimo++;
                            escritos++;
                        }
                    } else if (seq <= ultimo || retidos.containsKey(seq)) {
                        System.out.println("DUPLICADO: " + seq);
                    } else {
                        retidos.put(seq, r);
                        System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
                    }
                } catch (IOException e) {
                    System.out.println("IO: " + e.getMessage());
                }
                if (recebidos % 200 == 0) {
                    System.out.println("Resumo: recebidos=" + recebidos + " escritos=" + escritos + " retidos=" + retidos.size() + " primeira=" + primeira + " ultima=" + ultima);
                }
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}