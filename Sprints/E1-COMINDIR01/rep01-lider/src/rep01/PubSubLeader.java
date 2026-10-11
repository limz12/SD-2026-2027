package rep01;

import java.io.*;
import java.util.Scanner;

public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";
    static final String DEFAULT_API = "http://127.0.0.1:5001/api/v0";

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : DEFAULT_API;
        IpfsPubSub pubsub = new IpfsPubSub(api);
        try (Scanner sc = new Scanner(System.in)) {
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
                        file.append(rRajada); // 1) escreve localmente
                        pubsub.publish(TOPIC, rRajada.toLine()); // 2) publica no tópico
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
                pubsub.publish(TOPIC, r.toLine()); // 2) publica no tópico
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("IO: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
