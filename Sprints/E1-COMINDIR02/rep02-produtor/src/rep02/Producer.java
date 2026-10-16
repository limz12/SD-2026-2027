package rep02;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

public class Producer {

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub ps = new IpfsPubSub(api);
        Random rnd = new Random();
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Produtor pronto (entrada " + Wire.topicIn(0) + ", API " + api + ").");
            System.out.println("Comandos: <sensor> <temperatura> | rajada <n> <s> | sair");

            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");

                if (p[0].equalsIgnoreCase("rajada")) {
                    if (p.length != 3) {
                        System.out.println("Uso: rajada <n> <s>");
                        continue;
                    }
                    int n, s;
                    try {
                        n = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);
                    } catch (NumberFormatException e) {
                        System.out.println("Uso: rajada <n> <s>");
                        continue;
                    }
                    if (n <= 0 || s <= 0) {
                        System.out.println("Uso: rajada <n> <s>");
                        continue;
                    }
                    long t0 = System.currentTimeMillis();
                    long t1 = t0;
                    for (int i = 0; i < n; i++) {
                        String sensor = String.format("S%02d", 1 + rnd.nextInt(s));
                        double temp = Math.round((15 + rnd.nextDouble() * 15) * 10) / 10.0;
                        try {
                            ps.publish(Wire.topicIn(0), Wire.in(sensor, temp));
                        } catch (IOException | InterruptedException e) {
                            System.out.println("Falha a publicar: " + e.getMessage());
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        t1 = System.currentTimeMillis();
                    }
                    long dur = Math.max(1, t1 - t0);
                    System.out.println("Rajada de " + n + " registos para " + Wire.topicIn(0));
                    System.out.println("  inicio em " + t0 + " ms | fim em " + t1 + " ms");
                    System.out.println("  tempo total = " + dur + " ms | debito = " + (n * 1000L / dur) + " reg/s");
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato invalido. Uso: <sensor> <temperatura> | rajada <n> <s> | sair");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura invalida: " + p[1]);
                    continue;
                }
                try {
                    ps.publish(Wire.topicIn(0), Wire.in(p[0], temp));
                    System.out.println("Enviado para " + Wire.topicIn(0) + ": " + Wire.in(p[0], temp));
                } catch (IOException | InterruptedException e) {
                    System.out.println("Falha a publicar: " + e.getMessage());
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }
}
