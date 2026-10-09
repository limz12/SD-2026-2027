import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static Wire.in;
import static Wire.topicIn;

public class Producer {
    public static void main(String[] args) throws Exception {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub ps = new IpfsPubSub(api);

        BufferedReader cli = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String linha;
        while ((linha = cli.readLine()) != null) {
            linha = linha.trim();
            if (linha.isEmpty()) {
                continue;
            }
            String[] p = linha.split("\\s+");
            if (p[0].equals("rajada")) {
                if (p.length < 3) {
                    System.out.println("Uso: rajada <n> <s>");
                    continue;
                }
                try {
                    int nRajada = Integer.parseInt(p[1]);
                    int s = Integer.parseInt(p[2]);

                    long t0 = System.currentTimeMillis();
                    Random rand = new Random();

                    for (int i = 0; i < nRajada; i++) {
                        int sensorId = rand.nextInt(s) + 1;
                        String sensor = String.format("S%02d", sensorId);
                        double temperatura = 15 + Math.random() * 15;

                        ps.publish(topicIn(0), Wire.in(sensor, temperatura));
                    }

                    long t1 = System.currentTimeMillis();

                    System.out.println("t0 = " + t0);
                    System.out.println("t1 = " + t1);
                    System.out.println("Duração da rajada: " + (t1 - t0) + " ms");
                    System.out.println("Partição p0: " + nRajada + " mensagens publicadas.");

                } catch (NumberFormatException e) {
                    System.out.println("Argumentos inválidos: " + linha);
                }
            } else if (p.length >= 2) {
                try {
                    String sensor = p[0];
                    double temperatura = Double.parseDouble(p[1]);
                    ps.publish(topicIn(0), Wire.in(sensor, temperatura));
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + linha);
                }
            } else {
                System.out.println("Comando inválido: " + linha);
            }
        }
    }
}