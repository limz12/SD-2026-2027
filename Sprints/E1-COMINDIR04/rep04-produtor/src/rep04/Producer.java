package rep04;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

public class Producer {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: Producer <N> [api]");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("N invalido: " + args[0]);
            return;
        }
        if (n <= 0) {
            System.out.println("N tem de ser > 0");
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub ps = new IpfsPubSub(api);
        Partitioner partitioner = new Partitioner(n);
        Random rnd = new Random();
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Produtor pronto (N=" + n + ", API " + api + ").");
            System.out.println("Comandos: <sensor> <temperatura> | rajada <n> <sensores>  | rajada-quente <n> <sensores>  | sair");

            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");

                if (p[0].equalsIgnoreCase("rajada-quente")) {
                    if (p.length != 3) {
                        System.out.println("Uso: rajada-quente <n> <sensores> ");
                        continue;
                    }
                    int nR, s;
                    try {
                        nR = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);
                    } catch (NumberFormatException e) {
                        System.out.println("Uso: rajada-quente <n> <sensores> ");
                        continue;
                    }
                    if (nR <= 0 || s < 2) {
                        System.out.println("Uso: rajada-quente <n> <sensores>  (s >= 2)");
                        continue;
                    }
                    try {
                        executarRajada(ps, partitioner, rnd, nR, s, true);
                    } catch (IOException | InterruptedException e) {
                        System.out.println("Falha a publicar: " + e.getMessage());
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    continue;
                }

                if (p[0].equalsIgnoreCase("rajada")) {
                    if (p.length != 3) {
                        System.out.println("Uso: rajada <n> <sensores> ");
                        continue;
                    }
                    int nR, s;
                    try {
                        nR = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);
                    } catch (NumberFormatException e) {
                        System.out.println("Uso: rajada <n> <sensores> ");
                        continue;
                    }
                    if (nR <= 0 || s <= 0) {
                        System.out.println("Uso: rajada <n> <sensores> ");
                        continue;
                    }
                    try {
                        executarRajada(ps, partitioner, rnd, nR, s, false);
                    } catch (IOException | InterruptedException e) {
                        System.out.println("Falha a publicar: " + e.getMessage());
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato invalido. Uso: <sensor> <temperatura> | rajada <n> <sensores>  | rajada-quente <n> <sensores>  | sair");
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
                    publicar(ps, partitioner, p[0], temp);
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

    static void publicar(IpfsPubSub ps, Partitioner partitioner, String sensor, double temp) throws IOException, InterruptedException {
        int k = partitioner.of(sensor);
        ps.publish(Wire.topicIn(k), Wire.in(sensor, temp, 0, new VectorClock(partitioner.count())));
        System.out.println("Enviado para " + Wire.topicIn(k) + " (p" + k + "): " + Wire.in(sensor, temp, 0, new VectorClock(partitioner.count())));
    }

    static void executarRajada(IpfsPubSub ps, Partitioner partitioner, Random rnd, int nR, int s, boolean quente) throws IOException, InterruptedException {
        long t0 = System.currentTimeMillis();
        long t1 = t0;
        for (int i = 0; i < nR; i++) {
            String sensor;
            if (quente) {
                double u = rnd.nextDouble();
                if (u < 0.80) {
                    sensor = "S01";
                } else {
                    sensor = String.format("S%02d", 2 + rnd.nextInt(s - 1));
                }
            } else {
                sensor = String.format("S%02d", 1 + rnd.nextInt(s));
            }
            double temp = Math.round((15 + rnd.nextDouble() * 15) * 10) / 10.0;
            int k = partitioner.of(sensor);
            ps.publish(Wire.topicIn(k), Wire.in(sensor, temp, 0, new VectorClock(partitioner.count())));
            System.out.println("Enviado para " + Wire.topicIn(k) + " (p" + k + "): " + Wire.in(sensor, temp, 0, new VectorClock(partitioner.count())));
            t1 = System.currentTimeMillis();
        }
        long dur = Math.max(1, t1 - t0);
        String tipo = quente ? "rajada-quente" : "rajada";
        System.out.println(tipo + " de " + nR + " registos");
        System.out.println("  inicio em " + t0 + " ms | fim em " + t1 + " ms");
        System.out.println("  tempo total = " + dur + " ms | debito = " + (nR * 1000L / dur) + " reg/s");
    }
}
