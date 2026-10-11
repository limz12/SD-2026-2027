package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
                if (p[0].equalsIgnoreCase("rajada")) {
                    if (p.length != 2) {
                        System.out.println("Formato inválido. Uso: rajada <n>");
                        continue;
                    }
                    int n;
                    try {
                        n = Integer.parseInt(p[1]);
                    } catch (NumberFormatException e) {
                        System.out.println("Rajada inválida: " + p[1]);
                        continue;
                    }
                    if (n <= 0) {
                        System.out.println("Rajada inválida: " + p[1]);
                        continue;
                    }
                    long inicio = System.currentTimeMillis();
                    for (int i = 0; i < n; i++) {
                        double tempRajada = 15 + Math.random() * 15;
                        SensorRecord rRajada = SensorRecord.now(++seq, "SIM", tempRajada);
                        file.append(rRajada); // escreve localmente
                        byte[] mRajada = rRajada.toLine().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(mRajada, mRajada.length, group, PORT)); // propaga para o grupo
                    }
                    long fim = System.currentTimeMillis();
                    System.out.println("Rajada " + n + ": inicio=" + inicio + " fim=" + fim);
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
