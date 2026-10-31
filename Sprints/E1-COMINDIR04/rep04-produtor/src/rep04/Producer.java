package rep04;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.Scanner;

public class Producer {

    static int N;
    static Partitioner partitioner;
    static IpfsPubSub ps;

    // COMINDIR04: monitores de batimentos, um por partição
    static HeartbeatMonitor[] monitors;
    static final Path MONITOR_FILE = Paths.get("monitor-produtor.txt");

    /**
     * Escreve um evento em monitor-produtor.txt com o instante
     * físico atual como prefixo, e imprime-o na consola.
     *
     * Formato da linha: instante;EVENTO;p<k>;detalhes...
     */
    static synchronized void logEvento(String evento) {
        System.out.println(evento);
        try {
            Files.writeString(
                MONITOR_FILE,
                System.currentTimeMillis() + ";" + evento
                    + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.out.println(
                "Falha a registar evento no monitor: " + e.getMessage()
            );
        }
    }

    /**
     * Processa um LOTE recebido no tópico de dados p<k>.
     * Interpreta apenas o cabeçalho, para extrair
     * a encarnação e o contador de heartbeat.
     */
    static void onLoteHeader(int k, String msg) {
        String cabecalho = msg.split("\n", 2)[0].trim();
        String[] f = cabecalho.split(";", -1);
        if (f.length != 7 || !f[0].equals("LOTE")) {
            return;
        }
        try {
            monitors[k].onHeartbeat(Long.parseLong(f[3]), Long.parseLong(f[4]));
        } catch (NumberFormatException e) {
            System.out.println("REJEITADO: cabeçalho mal formado: " + cabecalho);
        }
    }

    static void publicar(String sensor, double temp) throws IOException, InterruptedException {
        int k = partitioner.of(sensor);

        // Avisar quando o líder está suspeito; publica na mesma,
        // porque o produtor não retém nem reenvia leituras
        if (monitors[k].state() == HeartbeatMonitor.State.SUSPEITO) {
            System.out.println(
                "AVISO: líder p" + k + " suspeito; leitura "
                + sensor + " pode perder-se"
            );
        }

        ps.publish(Wire.topicIn(k), Wire.in(sensor, temp, 0, new VectorClock(partitioner.count())));
        System.out.println("Enviado para " + Wire.topicIn(k) + " (p" + k + "): " + Wire.in(sensor, temp, 0, new VectorClock(partitioner.count())));
    }

    static void executarRajada(Random rnd, int nR, int s, boolean quente) throws IOException, InterruptedException {
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

            if (monitors[k].state() == HeartbeatMonitor.State.SUSPEITO) {
                System.out.println(
                    "AVISO: líder p" + k + " suspeito; leitura "
                    + sensor + " pode perder-se"
                );
            }

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

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: Producer <N> [api]");
            return;
        }
        try {
            N = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("N invalido: " + args[0]);
            return;
        }
        if (N <= 0) {
            System.out.println("N tem de ser > 0");
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        ps = new IpfsPubSub(api);
        partitioner = new Partitioner(N);
        Random rnd = new Random();

        // COMINDIR04: um monitor de batimentos por partição.
        // Os eventos vão para a consola e para monitor-produtor.txt.
        monitors = new HeartbeatMonitor[N];
        for (int k = 0; k < N; k++) {
            monitors[k] = new HeartbeatMonitor(
                k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT,
                Producer::logEvento
            );
        }

        // COMINDIR04: uma thread de subscrição por partição,
        // para observar os LOTE e alimentar os monitores
        for (int i = 0; i < N; i++) {
            final int k = i;
            Thread t = new Thread(() -> {
                try {
                    ps.subscribe(Wire.topicData(k), msg -> onLoteHeader(k, msg));
                } catch (IOException e) {
                    System.out.println("Erro na subscricao p" + k + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    System.out.println("Subscricao interrompida p" + k);
                    Thread.currentThread().interrupt();
                }
            }, "sub-dados-p" + k);
            t.setDaemon(true);
            t.start();
        }

        // COMINDIR04: thread de verificação que verifica
        // periodicamente o silêncio de todos os líderes
        Thread verificacao = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Wire.VERIFICACAO_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int k = 0; k < N; k++) {
                    monitors[k].check();
                }
            }
        }, "verificacao");
        verificacao.setDaemon(true);
        verificacao.start();

        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Produtor pronto (N=" + N + ", API " + api + ").");
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
                        executarRajada(rnd, nR, s, true);
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
                        executarRajada(rnd, nR, s, false);
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
                    publicar(p[0], temp);
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
