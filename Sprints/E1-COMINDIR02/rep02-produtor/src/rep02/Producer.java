
package rep02;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

public class Producer {

    static IpfsPubSub ipfs;
    static Partitioner partitioner;

    // Probabilidade de escolher S01 na rajada-quente
    static final double PROB_QUENTE = 0.8;

    static int enviar(String sensor, double temp)
            throws IOException, InterruptedException {

        // Determina a partição do sensor
        int k = partitioner.of(sensor);

        String mensagem = Wire.in(sensor, temp);

        // Publica no tópico correspondente à partição
        ipfs.publish(Wire.topicIn(k), mensagem);

        return k;
    }

    public static void main(String[] args) {

        // N obrigatório e API opcional
        if (args.length < 1 || args.length > 2) {
            System.out.println("Uso: Producer <N> [api]");
            return;
        }

        // Validar número de partições
        int N;

        try {
            N = Integer.parseInt(args[0]);

            if (N <= 0) {
                System.out.println(
                    "Erro: N deve ser um inteiro positivo."
                );
                return;
            }

        } catch (NumberFormatException e) {
            System.out.println(
                "Erro: N deve ser um inteiro positivo."
            );
            return;
        }

        // Criar o particionador
        partitioner = new Partitioner(N);

        String api = args.length > 1
                ? args[1]
                : "http://127.0.0.1:5001/api/v0";

        ipfs = new IpfsPubSub(api);

        Random rnd = new Random();

        try (Scanner sc = new Scanner(System.in)) {

            System.out.println(
                "Produtor PubSub pronto ("
                + N + " partições, API " + api + ")."
            );

            System.out.println(
                "Comandos: <sensor> <temperatura> | "
                + "rajada <n> <s> | "
                + "rajada-quente <n> <s> | sair"
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

                // Comandos rajada e rajada-quente
                boolean quente =
                    p[0].equalsIgnoreCase("rajada-quente");

                boolean normal =
                    p[0].equalsIgnoreCase("rajada");

                if (normal || quente) {

                    if (p.length != 3) {
                        System.out.println(
                            "Uso: " + p[0] + " <n> <s>"
                        );
                        continue;
                    }

                    int n;
                    int s;

                    try {
                        n = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);

                        // Validação dos valores
                        if (n <= 0 || s <= 0) {
                            System.out.println(
                                "Erro: n e s devem ser "
                                + "inteiros positivos."
                            );
                            continue;
                        }

                        // Na rajada-quente são necessários
                        // pelo menos dois sensores
                        if (quente && s < 2) {
                            System.out.println(
                                "Erro: rajada-quente exige s >= 2."
                            );
                            continue;
                        }

                    } catch (NumberFormatException e) {
                        System.out.println(
                            "Erro: n e s devem ser inteiros."
                        );
                        continue;
                    }

                    // Contadores por partição
                    int[] contador = new int[N];

                    // Instantes de publicação
                    long primeiro = 0;
                    long ultimo = 0;

                    // Geração e publicação da rajada
                    for (int i = 0; i < n; i++) {

                        int numero;

                        if (quente) {

                            // Rajada-quente:
                            // 80% das vezes escolhe S01
                            // 20% escolhe entre S02 e S<s>
                            if (rnd.nextDouble() < PROB_QUENTE) {

                                numero = 1;

                            } else {

                                numero = rnd.nextInt(s - 1) + 2;
                            }

                        } else {

                            // Rajada normal:
                            // escolha uniforme entre os s sensores
                            numero = rnd.nextInt(s) + 1;
                        }

                        // Identificador do sensor
                        String nSensor = String.format(
                            "S%02d", numero
                        );

                        // Temperatura aleatória entre 15 e 30
                        double temp =
                            15 + rnd.nextDouble() * 15;

                        if (i == 0) {
                            primeiro = System.currentTimeMillis();
                        }

                        // Publica na partição calculada
                        int k = enviar(nSensor, temp);

                        // Incrementa o contador da partição
                        contador[k]++;

                        // Instante após a publicação
                        ultimo = System.currentTimeMillis();

                        System.out.println(
                            "Leitura enviada: "
                            + Wire.in(nSensor, temp)
                            + " → p" + k
                        );
                    }

                    // Apresentar resultados
                    long dur = Math.max(1, ultimo - primeiro);

                    System.out.println(
                        (quente ? "Rajada-quente" : "Rajada")
                        + " de " + n + " leituras enviada."
                    );

                    System.out.println(
                        "Primeira publicação: "
                        + primeiro + " ms"
                    );

                    System.out.println(
                        "Última publicação: "
                        + ultimo + " ms"
                    );

                    System.out.println(
                        "Tempo entre publicações: "
                        + dur + " ms"
                    );

                    System.out.println(
                        "Débito aproximado: "
                        + (n * 1000L / dur)
                        + " leituras/s"
                    );

                    // Totais por partição
                    System.out.println(
                        "Leituras enviadas por partição:"
                    );

                    for (int k = 0; k < N; k++) {

                        System.out.println(
                            "  p" + k + ": "
                            + contador[k] + " leituras"
                        );
                    }

                    continue;
                }

                // Leitura normal
                if (p.length != 2) {

                    System.out.println(
                        "Formato inválido. Use: "
                        + "<sensor> <temperatura>"
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

                // Publicar na partição do sensor
                int k = enviar(p[0], temp);

                System.out.println(
                    "Leitura enviada: "
                    + Wire.in(p[0], temp)
                    + " → p" + k
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