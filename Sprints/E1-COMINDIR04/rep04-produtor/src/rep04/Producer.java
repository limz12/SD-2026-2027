
package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

public class Producer {

    static final Object LOCK_LOG = new Object();

    // Um monitor por partição
    static HeartbeatMonitor[] monitores;

    // Ficheiro de eventos
    static final Path ficheiroMonitor =
            Paths.get("monitor-produtor.txt");

    /**
     * Escreve um evento na consola e no ficheiro.
     *
     * Formato:
     * instante;evento
     *
     * O instante é medido com currentTimeMillis().
     * A deteção de silêncio pertence ao
     * HeartbeatMonitor, que utiliza nanoTime().
     */
    static void registar(String evento) {

        String linha =
                System.currentTimeMillis()
                + ";" + evento;

        synchronized (LOCK_LOG) {

            System.out.println(linha);

            try {

                Files.writeString(
                    ficheiroMonitor,
                    linha + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );

            } catch (IOException e) {

                System.err.println(
                    "Falha a escrever monitor: "
                    + e.getMessage()
                );
            }
        }
    }

    /**
     * Interpreta apenas o cabeçalho de um LOTE.
     *
     * Formato:
     * LOTE;endereco;porto;inc;hb;ultimoSeq;n
     *
     * Não descodifica nem percorre os registos
     * existentes nas linhas seguintes.
     */
    static void onLote(int k, String msg) {

        try {

            if (msg == null) {
                throw new IllegalArgumentException(
                    "Mensagem nula"
                );
            }

            // Obter apenas a primeira linha
            int fim = msg.indexOf('\n');

            String cabecalho = fim >= 0
                    ? msg.substring(0, fim)
                    : msg;

            cabecalho = cabecalho.trim();

            String[] f = cabecalho.split(";", -1);

            if (f.length != 7
                    || !f[0].equals("LOTE")) {

                throw new IllegalArgumentException(
                    "Cabeçalho LOTE mal formado"
                );
            }

            // Campos do cabeçalho
            String endereco = f[1].trim();

            int porto = Integer.parseInt(
                f[2].trim()
            );

            long inc = Long.parseLong(
                f[3].trim()
            );

            long hb = Long.parseLong(
                f[4].trim()
            );

            long ultimoSeq = Long.parseLong(
                f[5].trim()
            );

            int n = Integer.parseInt(
                f[6].trim()
            );

            // Validar o cabeçalho
            if (endereco.isEmpty()
                    || porto <= 0
                    || porto > 65535
                    || inc < 0
                    || hb < 0
                    || ultimoSeq < 0
                    || n < 0) {

                throw new IllegalArgumentException(
                    "Valores inválidos no cabeçalho"
                );
            }

            // Comunicar o heartbeat ao monitor.
            // O monitor gere SUSPEITA, RETOMA,
            // REINICIO e HB_PERDIDO.
            monitores[k].onHeartbeat(inc, hb);

            // Assinalar a receção do heartbeat
            registar(
                "RECEBIDO;p" + k
                + ";inc=" + inc
                + ";hb=" + hb
                + ";ultimoSeq=" + ultimoSeq
                + ";n=" + n
            );

        } catch (IllegalArgumentException e) {

            registar(
                "REJEITADO;p" + k
                + ";erro=" + e.getMessage()
            );
        }
    }

    /**
     * Publica uma leitura IN na partição correta.
     *
     * Antes da publicação, verifica se o líder
     * dessa partição está SUSPEITO.
     *
     * Mesmo em caso de suspeita, envia a leitura.
     *
     * Devolve a partição utilizada.
     */
    static int publicar(
            IpfsPubSub ps,
            Partitioner partitioner,
            String sensor,
            double temp)
            throws IOException, InterruptedException {

        int k = partitioner.of(sensor);

        // Verificar o estado do líder de destino
        if (monitores[k].state()
                == HeartbeatMonitor.State.SUSPEITO) {

            registar(
                "AVISO: líder p" + k
                + " suspeito; leitura "
                + sensor
                + " pode perder-se"
            );
        }

        // Formato IN da COMINDIR03/04.
        // O produtor envia os relógios a zero.
        String mensagem = Wire.in(
            sensor,
            temp,
            0,
            new VectorClock(partitioner.count())
        );

        // Publicar mesmo que o líder esteja suspeito
        ps.publish(
            Wire.topicIn(k),
            mensagem
        );

        System.out.println(
            "Enviado para " + Wire.topicIn(k)
            + " (p" + k + "): " + mensagem
        );

        return k;
    }

    /**
     * Executa uma rajada normal ou quente.
     *
     * A publicação é sempre feita através
     * de publicar(), garantindo a verificação
     * do HeartbeatMonitor em cada leitura.
     */
    static void executarRajada(
            IpfsPubSub ps,
            Partitioner partitioner,
            Random rnd,
            int nR,
            int s,
            boolean quente)
            throws IOException, InterruptedException {

        long t0 = System.currentTimeMillis();
        long t1 = t0;

        // Contador de leituras por partição
        int[] contador = new int[partitioner.count()];

        for (int i = 0; i < nR; i++) {

            String sensor;

            if (quente) {

                double u = rnd.nextDouble();

                if (u < 0.80) {

                    // Sensor quente com 80% de probabilidade
                    sensor = "S01";

                } else {

                    // Restantes sensores
                    sensor = String.format(
                        "S%02d",
                        2 + rnd.nextInt(s - 1)
                    );
                }

            } else {

                // Seleção uniforme de sensores
                sensor = String.format(
                    "S%02d",
                    1 + rnd.nextInt(s)
                );
            }

            // Temperatura entre 15 e 30 graus
            double temp = Math.round(
                (15 + rnd.nextDouble() * 15) * 10
            ) / 10.0;

            // Publicação centralizada:
            // verifica SUSPEITO antes de enviar
            int k = publicar(
                ps,
                partitioner,
                sensor,
                temp
            );

            contador[k]++;

            t1 = System.currentTimeMillis();
        }

        long dur = Math.max(1, t1 - t0);

        String tipo = quente
                ? "rajada-quente"
                : "rajada";

        System.out.println(
            tipo + " de " + nR + " registos"
        );

        System.out.println(
            "  início em " + t0
            + " ms | fim em " + t1 + " ms"
        );

        System.out.println(
            "  tempo total = " + dur
            + " ms | débito = "
            + (nR * 1000L / dur)
            + " reg/s"
        );

        System.out.println(
            "  Registos por partição:"
        );

        for (int k = 0; k < contador.length; k++) {

            System.out.println(
                "    p" + k + ": " + contador[k]
            );
        }
    }

    /**
     * Inicia uma thread de subscrição para
     * cada partição.
     *
     * As threads recebem apenas os cabeçalhos
     * dos lotes para atualizar os monitores.
     */
    static List<Thread> iniciarSubscricoes(
            IpfsPubSub ps, int n) {

        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < n; i++) {

            final int k = i;

            Thread t = new Thread(() -> {

                try {

                    ps.subscribe(
                        Wire.topicData(k),
                        msg -> onLote(k, msg)
                    );

                } catch (IOException e) {

                    registar(
                        "SUBSCRICAO_FALHOU;p" + k
                        + ";erro=" + e.getMessage()
                    );

                } catch (InterruptedException e) {

                    registar(
                        "SUBSCRICAO_INTERROMPIDA;p" + k
                    );

                    Thread.currentThread().interrupt();
                }

            }, "produtor-hb-p" + k);

            // Permite terminar a aplicação ao sair
            t.setDaemon(true);
            t.start();

            threads.add(t);

            registar(
                "SUBSCRICAO;p" + k
                + ";topic=" + Wire.topicData(k)
            );
        }

        return threads;
    }

    /**
     * Inicia a verificação periódica de todos
     * os monitores de heartbeat.
     */
    static Thread iniciarVerificacao(int n) {

        Thread verificacao = new Thread(() -> {

            while (!Thread.currentThread().isInterrupted()) {

                try {

                    Thread.sleep(
                        Wire.VERIFICACAO_MS
                    );

                    for (int k = 0; k < n; k++) {

                        monitores[k].check();
                    }

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                }
            }

        }, "produtor-verificacao-heartbeats");

        verificacao.setDaemon(true);
        verificacao.start();

        return verificacao;
    }

    /**
     * Execução:
     * Producer <N> [api]
     */
    public static void main(String[] args) {

        if (args.length < 1 || args.length > 2) {

            System.out.println(
                "Uso: Producer <N> [api]"
            );
            return;
        }

        int n;

        try {

            n = Integer.parseInt(args[0]);

        } catch (NumberFormatException e) {

            System.out.println(
                "N inválido: " + args[0]
            );
            return;
        }

        if (n <= 0) {

            System.out.println(
                "N tem de ser > 0"
            );
            return;
        }

        // Validar os parâmetros dos monitores
        if (Wire.PERIODO_HB_MS <= 0
                || Wire.FATOR_TIMEOUT <= 0
                || Wire.VERIFICACAO_MS <= 0) {

            System.out.println(
                "Parâmetros de heartbeat inválidos."
            );
            return;
        }

        String api = args.length > 1
                ? args[1]
                : "http://127.0.0.1:5001/api/v0";

        IpfsPubSub ps = new IpfsPubSub(api);

        Partitioner partitioner = new Partitioner(n);

        Random rnd = new Random();

        // Um HeartbeatMonitor separado por partição
        monitores = new HeartbeatMonitor[n];

        for (int k = 0; k < n; k++) {

            monitores[k] = new HeartbeatMonitor(
                k,
                Wire.PERIODO_HB_MS,
                Wire.FATOR_TIMEOUT,
                Producer::registar
            );
        }

        // Iniciar as subscrições LOTE
        iniciarSubscricoes(ps, n);

        // Iniciar a verificação periódica
        iniciarVerificacao(n);

        registar(
            "MONITORIZACAO_INICIADA"
            + ";particoes=" + n
            + ";periodo=" + Wire.PERIODO_HB_MS
            + ";fator=" + Wire.FATOR_TIMEOUT
            + ";verificacao=" + Wire.VERIFICACAO_MS
        );

        try (Scanner sc = new Scanner(System.in)) {

            System.out.println(
                "Produtor pronto (N=" + n
                + ", API " + api + ")."
            );

            System.out.println(
                "Comandos: <sensor> <temperatura> "
                + "| rajada <n> <sensores> "
                + "| rajada-quente <n> <sensores> "
                + "| sair"
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
                    continue;
                }

                String[] p = line.split("\\s+");

                // Comando rajada-quente
                if (p[0].equalsIgnoreCase(
                        "rajada-quente")) {

                    if (p.length != 3) {

                        System.out.println(
                            "Uso: rajada-quente "
                            + "<n> <sensores>"
                        );
                        continue;
                    }

                    int nR;
                    int s;

                    try {

                        nR = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);

                    } catch (NumberFormatException e) {

                        System.out.println(
                            "Uso: rajada-quente "
                            + "<n> <sensores>"
                        );
                        continue;
                    }

                    if (nR <= 0 || s < 2) {

                        System.out.println(
                            "Uso: rajada-quente "
                            + "<n> <sensores> (s >= 2)"
                        );
                        continue;
                    }

                    try {

                        executarRajada(
                            ps,
                            partitioner,
                            rnd,
                            nR,
                            s,
                            true
                        );

                    } catch (IOException
                            | InterruptedException e) {

                        System.out.println(
                            "Falha a publicar: "
                            + e.getMessage()
                        );

                        if (e instanceof InterruptedException) {

                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    continue;
                }

                // Comando rajada
                if (p[0].equalsIgnoreCase("rajada")) {

                    if (p.length != 3) {

                        System.out.println(
                            "Uso: rajada <n> <sensores>"
                        );
                        continue;
                    }

                    int nR;
                    int s;

                    try {

                        nR = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);

                    } catch (NumberFormatException e) {

                        System.out.println(
                            "Uso: rajada <n> <sensores>"
                        );
                        continue;
                    }

                    if (nR <= 0 || s <= 0) {

                        System.out.println(
                            "Uso: rajada <n> <sensores>"
                        );
                        continue;
                    }

                    try {

                        executarRajada(
                            ps,
                            partitioner,
                            rnd,
                            nR,
                            s,
                            false
                        );

                    } catch (IOException
                            | InterruptedException e) {

                        System.out.println(
                            "Falha a publicar: "
                            + e.getMessage()
                        );

                        if (e instanceof InterruptedException) {

                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    continue;
                }

                // Leitura individual
                if (p.length != 2) {

                    System.out.println(
                        "Formato inválido. Uso: "
                        + "<sensor> <temperatura> "
                        + "| rajada <n> <sensores> "
                        + "| rajada-quente <n> <sensores> "
                        + "| sair"
                    );
                    continue;
                }

                double temp;

                try {

                    temp = Double.parseDouble(p[1]);

                } catch (NumberFormatException e) {

                    System.out.println(
                        "Temperatura inválida: "
                        + p[1]
                    );
                    continue;
                }

                if (!Double.isFinite(temp)) {

                    System.out.println(
                        "Temperatura inválida: "
                        + p[1]
                    );
                    continue;
                }

                try {

                    publicar(
                        ps,
                        partitioner,
                        p[0],
                        temp
                    );

                } catch (IOException
                        | InterruptedException e) {

                    System.out.println(
                        "Falha a publicar: "
                        + e.getMessage()
                    );

                    if (e instanceof InterruptedException) {

                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }
}
