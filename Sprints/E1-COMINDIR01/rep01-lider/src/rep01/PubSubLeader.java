package rep01;

import java.io.*;
import java.util.Random;
import java.util.Scanner;

public class PubSubLeader {
    static final String TOPICO = "rep01-temperaturas";

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub pubsub = new IpfsPubSub(api);
        try (Scanner sc = new Scanner(System.in)) {
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();
            System.out.println("Líder pronto. API=" + api + " | Tópico=" + TOPICO
                    + " | Formato: <sensor> <temperatura> | 'sair' para terminar");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\\s+");
                if (p[0].equalsIgnoreCase("rajada") && p.length == 2) {
                    try {
                        int n = Integer.parseInt(p[1]);
                        Random rd = new Random();
                        long tPrimeiro = 0, tUltimo = 0;
                        for (int i = 0; i < n; i++) {
                            SensorRecord r = SensorRecord.now(++seq, "SIM", 15 + rd.nextDouble() * 15);
                            if (i == 0) tPrimeiro = System.currentTimeMillis();
                            file.append(r);
                            pubsub.publish(TOPICO, r.toLine());
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
                file.append(r);
                pubsub.publish(TOPICO, r.toLine());
                System.out.println("Registado e publicado: " + r.toLine());
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
