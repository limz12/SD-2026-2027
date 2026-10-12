package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Scanner;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1); // o datagrama não sai da rede local
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq(); // continua a numeração após reinício
            System.out.println("Líder pronto. Formato: <sensor> <temperatura> | 'sair' para terminar");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break; // bloqueia à espera do utilizador
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\\s+");
                if (p[0].equalsIgnoreCase("desordem") && p.length == 3) {
                    try {
                        double t = Double.parseDouble(p[2]);
                        SensorRecord r1 = SensorRecord.now(seq + 1, p[1], t);
                        SensorRecord r2 = SensorRecord.now(seq + 2, p[1], t + 0.1);
                        SensorRecord r3 = SensorRecord.now(seq + 3, p[1], t + 0.2);
                        byte[] m2 = r2.toLine().getBytes(StandardCharsets.UTF_8);
                        byte[] m3 = r3.toLine().getBytes(StandardCharsets.UTF_8);
                        byte[] m1 = r1.toLine().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(m2, m2.length, group, PORT));
                        socket.send(new DatagramPacket(m3, m3.length, group, PORT));
                        socket.send(new DatagramPacket(m1, m1.length, group, PORT));
                        file.append(r1); file.append(r2); file.append(r3);
                        seq += 3;
                        System.out.println("Enviado 2,3,1; ficheiro em ordem: " + r1.toLine());
                    } catch (NumberFormatException | IOException e) {
                        System.out.println("Erro no desordem: " + e.getMessage());
                    }
                    continue;
                }
                if (p[0].equalsIgnoreCase("rajada") && p.length == 2) {
                    try {
                        int n = Integer.parseInt(p[1]);
                        Random rd = new Random();
                        long tPrimeiro = 0, tUltimo = 0;
                        for (int i = 0; i < n; i++) {
                            SensorRecord r = SensorRecord.now(++seq, "SIM", 15 + rd.nextDouble() * 15);
                            if (i == 0) tPrimeiro = System.currentTimeMillis();
                            file.append(r);
                            byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                            socket.send(new DatagramPacket(m, m.length, group, PORT));
                            if (i == n - 1) tUltimo = System.currentTimeMillis();
                        }
                        System.out.println("Rajada: tPrimeiro=" + tPrimeiro + " tUltimo=" + tUltimo);
                    } catch (NumberFormatException e) {
                        System.out.println("Rajada inválida: " + p[1]);
                    }
                    continue;
                }
                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r); // 1) escreve localmente
                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(m, m.length, group, PORT)); // 2) propaga para o grupo
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
