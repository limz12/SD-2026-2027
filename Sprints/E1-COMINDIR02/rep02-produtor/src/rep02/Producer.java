package rep02;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.Scanner;

/** Produtor interativo que encaminha as leituras para o tópico da partição. */
public final class Producer {
    private static final String API_POR_OMISSAO = "http://127.0.0.1:5001/api/v0";

    private final Partitioner partitioner;
    private final IpfsPubSub pubSub;
    private final String api;
    private final Random random = new Random();

    private Producer(int partitions, String api) {
        this.partitioner = new Partitioner(partitions);
        this.api = api;
        this.pubSub = new IpfsPubSub(api);
    }

    public static void main(String[] args) {
        try {
            int partitions = args.length >= 1 ? Integer.parseInt(args[0]) : 3;
            String api = args.length >= 2 ? args[1] : API_POR_OMISSAO;
            if (args.length > 2) {
                throw new IllegalArgumentException("Uso: Producer <N> [api]");
            }
            Producer producer = new Producer(partitions, api);
            producer.run();
        } catch (IllegalArgumentException e) {
            System.err.println("Argumentos inválidos: " + e.getMessage());
            System.err.println("Uso: Producer <N> [api]");
        }
    }

    private void run() {
        System.out.println("Produtor COMINDIR02: N=" + partitioner.count()
                + " | API=" + api);
        System.out.println("Comandos: <sensor> <temperatura> | rajada <n> <s>"
                + " | rajada-quente <n> <s> | sair");

        try (Scanner input = new Scanner(System.in)) {
            while (input.hasNextLine()) {
                String line = input.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("sair")) {
                    return;
                }
                String[] fields = line.split("\\s+");
                try {
                    if (fields[0].equalsIgnoreCase("rajada")
                            || fields[0].equalsIgnoreCase("rajada-quente")) {
                        if (fields.length != 3) {
                            throw new IllegalArgumentException("Uso: rajada <n> <s>"
                                    + " ou rajada-quente <n> <s>");
                        }
                        int count = positiveInt(fields[1], "número de leituras");
                        int sensors = positiveInt(fields[2], "número de sensores");
                        boolean hotKey = fields[0].equalsIgnoreCase("rajada-quente");
                        if (hotKey && sensors < 2) {
                            throw new IllegalArgumentException("rajada-quente requer s >= 2");
                        }
                        publishBurst(count, sensors, hotKey);
                    } else {
                        if (fields.length != 2) {
                            throw new IllegalArgumentException("Formato esperado: <sensor> <temperatura>");
                        }
                        double temperature = Double.parseDouble(fields[1]);
                        if (!Double.isFinite(temperature)) {
                            throw new IllegalArgumentException("A temperatura tem de ser finita");
                        }
                        publish(fields[0], temperature);
                    }
                } catch (IOException e) {
                    System.err.println("PUBLICAÇÃO FALHOU: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Publicação interrompida.");
                    return;
                } catch (IllegalArgumentException e) {
                    System.err.println("Comando inválido: " + e.getMessage());
                }
            }
        }
    }

    private void publish(String sensor, double temperature)
            throws IOException, InterruptedException {
        if (sensor.isBlank() || sensor.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Identificador de sensor inválido");
        }
        int partition = partitioner.of(sensor);
        publishToTopic(Wire.topicIn(partition), Wire.in(sensor, temperature));
        System.out.println("Publicado: " + sensor + ";" + temperature + " → p" + partition);
    }

    /**
     * Repete apenas a rejeição explícita "validation ignored" do Kubo. Outros
     * erros de I/O podem ter resultado ambíguo; repeti-los às cegas poderia
     * publicar a mesma leitura duas vezes.
     */
    private void publishToTopic(String topic, String message)
            throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                pubSub.publish(topic, message);
                return;
            } catch (IOException e) {
                if (!isValidationIgnored(e) || attempt == 5) {
                    throw e;
                }
                long delayMs = 200L << (attempt - 1);
                System.err.println("AVISO: Kubo respondeu 'validation ignored' (tentativa "
                        + attempt + " de 5); nova tentativa em " + delayMs + " ms.");
                Thread.sleep(delayMs);
            }
        }
    }

    private static boolean isValidationIgnored(IOException failure) {
        String message = failure.getMessage();
        return message != null
                && message.contains("pub falhou (500):")
                && message.contains("validation ignored");
    }

    private void publishBurst(int count, int sensors, boolean hotKey)
            throws IOException, InterruptedException {
        long firstPublication = 0;
        long lastPublication = 0;
        int[] perPartition = new int[partitioner.count()];
        int published = 0;

        for (int i = 0; i < count; i++) {
            String sensor;
            if (hotKey && random.nextDouble() < 0.80) {
                sensor = "S01";
            } else if (hotKey) {
                // A chave quente ocupa 80%; os restantes 20% usam S02..S<s>.
                sensor = sensorId(2 + random.nextInt(sensors - 1));
            } else {
                sensor = sensorId(1 + random.nextInt(sensors));
            }
            double temperature = 15.0 + random.nextInt(151) / 10.0;
            int partition = partitioner.of(sensor);
            publishToTopic(Wire.topicIn(partition), Wire.in(sensor, temperature));
            long publishedAt = System.currentTimeMillis();
            if (firstPublication == 0) {
                firstPublication = publishedAt;
            }
            lastPublication = publishedAt;
            perPartition[partition]++;
            published++;
        }

        long elapsed = Math.max(0, lastPublication - firstPublication);
        System.out.println("Rajada " + (hotKey ? "quente" : "uniforme") + ": "
                + published + "/" + count + " leituras publicadas.");
        System.out.println("  primeira publicação=" + firstPublication + " ms"
                + " | última publicação=" + lastPublication + " ms"
                + " | duração=" + elapsed + " ms");
        System.out.println("  leituras por partição: " + Arrays.toString(perPartition));
    }

    private String sensorId(int number) {
        return String.format(Locale.ROOT, "S%02d", number);
    }

    private static int positiveInt(String text, String description) {
        int value = Integer.parseInt(text);
        if (value < 1) {
            throw new IllegalArgumentException(description + " tem de ser >= 1");
        }
        return value;
    }

}
