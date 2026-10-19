
package rep02;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

public class Producer {

    static IpfsPubSub ipfs;

    static void enviar(String sensor, double temp)
            throws IOException, InterruptedException {

        String mensagem = Wire.in(sensor, temp);
        ipfs.publish(Wire.topicIn(0), mensagem);
    }

    public static void main(String[] args) {

        String api = args.length > 0
                ? args[0]
                : "http://127.0.0.1:5001/api/v0";

        ipfs = new IpfsPubSub(api);

        Random rnd = new Random();

        try (Scanner sc = new Scanner(System.in)) {

            System.out.println(
                "Produtor PubSub pronto (tópico "
                + Wire.topicIn(0) + ", API " + api + ")."
            );

            System.out.println(
                "Comandos: <sensor> <temperatura> | rajada <n> <s> | sair"
            );

            while (true) {

                System.out.print("> ");

                if (!sc.hasNextLine()) {
                    break;
                }

                String line = sc.nextLine().trim();

                if (line.equalsIgnoreCase("sair")) {
                    break;
                }

                if (line.isEmpty()) {
                    System.out.println("Formato inválido.");
                    continue;
                }

                String[] p = line.split("\\s+");

                // Comando rajada
                if (p[0].equalsIgnoreCase("rajada")) {

                    if (p.length != 3) {
                        System.out.println("Uso: rajada <n> <s>");
                        continue;
                    }

                    int n;
                    int s;

                    try {
                        n = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);

                        if (n <= 0 || s <= 0) {
                            System.out.println(
                                "Erro: n e s devem ser inteiros positivos."
                            );
                            continue;
                        }

                    } catch (NumberFormatException e) {
                        System.out.println(
                            "Erro: n e s devem ser inteiros positivos."
                        );
                        continue;
                    }

                    // Geração e publicação da rajada
                    long primeiro = 0;
                    long ultimo = 0;

                    for (int i = 0; i < n; i++) {

                        int numero = rnd.nextInt(s) + 1;

                        // Escolhe um dos s sensores
                        String nSensor = String.format("S%02d", numero);

                        // Temperatura aleatória entre 15 e 30
                        double temp = 15 + rnd.nextDouble() * 15;

                        if (i == 0) {
                            primeiro = System.currentTimeMillis();
                        }

                        // Publica a leitura
                        enviar(nSensor, temp);

                        // Instante após a publicação
                        long instante = System.currentTimeMillis();

                        ultimo = instante;
                    }

                    // Apresenta os resultados
                    long dur = Math.max(1, ultimo - primeiro);

                    System.out.println(
                        "Rajada de " + n + " leituras enviada."
                    );

                    System.out.println(
                        "Primeira publicação: " + primeiro + " ms"
                    );

                    System.out.println(
                        "Última publicação: " + ultimo + " ms"
                    );

                    System.out.println(
                        "Tempo entre publicações: " + dur + " ms"
                    );

                    System.out.println(
                        "Débito aproximado: "
                        + (n * 1000L / dur) + " leituras/s"
                    );

                    continue;
                }

                // Leitura normal
                if (p.length != 2) {
                    System.out.println(
                        "Formato inválido. Use: <sensor> <temperatura>"
                    );
                    continue;
                }

                double temp;

                try {
                    temp = Double.parseDouble(p[1]);

                    if (!Double.isFinite(temp)) {
                        System.out.println(
                            "Temperatura inválida: " + p[1]
                        );
                        continue;
                    }

                } catch (NumberFormatException e) {
                    System.out.println(
                        "Temperatura inválida: " + p[1]
                    );
                    continue;
                }

                enviar(p[0], temp);

                System.out.println(
                    "Leitura enviada: " + Wire.in(p[0], temp)
                );
            }

        } catch (IOException e) {

            System.out.println(
                "IO: " + e.getClass().getSimpleName()
                + ": " + e.getMessage()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }
}
