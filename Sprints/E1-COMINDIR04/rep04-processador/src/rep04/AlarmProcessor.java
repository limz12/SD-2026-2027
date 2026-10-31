package rep04;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

public class AlarmProcessor {

    // Estado partilhado entre todas as threads
    static final Object LOCK = new Object();

    static long Lp;
    static VectorClock Vp;

    static int N;
    static Partitioner partitioner;
    static IpfsPubSub ps;

    // Sequências já recebidas em cada partição
    static Set<Long>[] recebidos;

    // COMINDIR04: monitores de batimentos, um por partição
    static HeartbeatMonitor[] monitors;
    static final Path MONITOR_FILE = Paths.get("monitor-processador.txt");

    /**
     * Escreve um evento em monitor-processador.txt com o instante
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
     * Processa uma mensagem LOTE recebida
     * de uma determinada partição.
     */
    static void onLote(int k, String msg) {

        Batch batch;

        try {
            batch = Batch.decode(msg, N);
        } catch (IllegalArgumentException e) {
            System.out.println(
                "REJEITADO: p" + k + " (" + e.getMessage() + ")"
            );
            return;
        }

        // Atualizar o monitor de batimentos do líder p<k>
        monitors[k].onHeartbeat(batch.getInc(), batch.getHb());

        for (SensorRecord r : batch.getRecords()) {

            String sensor = r.getSensorId();
            long seq = r.getSeq();

            // Ignorar registos de alarmes antes
            // de atualizar os relógios do processador
            if (sensor.startsWith("ALARM-")) {
                continue;
            }

            // Relógios a utilizar no eventual alarme
            long novoLp;
            VectorClock novoVp;

            // SECÇÃO SINCRONIZADA
            synchronized (LOCK) {

                // Ignorar duplicados da mesma partição
                if (recebidos[k].contains(seq)) {

                    System.out.println(
                        "DUPLICADO: p" + k + " seq " + seq
                    );
                    continue;
                }

                // Marcar o registo como recebido
                recebidos[k].add(seq);

                // Atualizar o relógio de Lamport
                Lp = Math.max(Lp, r.getLamport());

                // Atualizar o relógio vetorial
                Vp.merge(r.getVector());

                // Guardar um estado consistente
                novoLp = Lp;
                novoVp = Vp.copy();
            }

            // Verificar se a temperatura atinge
            // o limiar definido na classe Wire
            if (r.getTemperature() < Wire.LIMIAR_ALARME) {
                continue;
            }

            // Construir o identificador do alarme
            String sensorAlarme = "ALARM-" + sensor;

            // Determinar a partição de destino
            int j = partitioner.of(sensorAlarme);

            // Avisar quando o líder de destino está suspeito:
            // detetar a falha não é recuperar o alarme
            if (monitors[j].state() == HeartbeatMonitor.State.SUSPEITO) {
                System.out.println(
                    "AVISO: líder p" + j + " suspeito; alarme "
                    + sensor + " pode perder-se"
                );
            }

            // Criar a mensagem IN com os relógios
            String mensagem = Wire.in(
                sensorAlarme,
                r.getTemperature(),
                novoLp,
                novoVp
            );

            // Publicação fora da secção sincronizada
            try {

                ps.publish(
                    Wire.topicIn(j),
                    mensagem
                );

                System.out.println(
                    "ALARME: " + sensor
                    + " p" + k + "#" + seq
                    + " → p" + j
                );

            } catch (IOException e) {

                System.out.println(
                    "Falha a publicar alarme de "
                    + sensor + ": " + e.getMessage()
                );

            } catch (InterruptedException e) {

                System.out.println(
                    "Publicação do alarme interrompida: "
                    + e.getMessage()
                );

                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Arranque do processador de alarmes.
     *
     * Argumentos:
     * AlarmProcessor <N> [api]
     */
    public static void main(String[] args) {

        // Validar argumentos
        if (args.length < 1 || args.length > 2) {

            System.out.println(
                "Uso: AlarmProcessor <N> [api]"
            );
            return;
        }

        // Validar número de partições
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

        // Endereço da API IPFS
        String api = args.length > 1
                ? args[1]
                : "http://127.0.0.1:5001/api/v0";

        partitioner = new Partitioner(N);
        ps = new IpfsPubSub(api);

        // Inicializar relógios do processador
        Lp = 0;
        Vp = new VectorClock(N);

        // Inicializar conjuntos de duplicados
        @SuppressWarnings("unchecked")
        Set<Long>[] conjuntos =
                (Set<Long>[]) new Set<?>[N];

        recebidos = conjuntos;

        for (int k = 0; k < N; k++) {
            recebidos[k] = new HashSet<>();
        }

        // COMINDIR04: um monitor de batimentos por partição.
        // Os eventos vão para a consola e para monitor-processador.txt.
        monitors = new HeartbeatMonitor[N];

        for (int k = 0; k < N; k++) {
            monitors[k] = new HeartbeatMonitor(
                k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT,
                AlarmProcessor::logEvento
            );
        }

        System.out.println(
            "Processador de alarmes iniciado."
        );

        System.out.println(
            "Número de partições: " + N
        );

        System.out.println(
            "API IPFS: " + api
        );

        System.out.println(
            "Limiar de alarme: "
            + Wire.LIMIAR_ALARME + " °C"
        );

        System.out.println(
            "Relógio inicial: Lp=" + Lp
            + " Vp=" + Vp
        );

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

        // Uma thread de subscrição por partição
        for (int i = 0; i < N; i++) {

            final int k = i;

            Thread t = new Thread(() -> {

                try {

                    ps.subscribe(
                        Wire.topicData(k),
                        msg -> onLote(k, msg)
                    );

                } catch (IOException e) {

                    System.out.println(
                        "Erro na subscrição p" + k
                        + ": " + e.getMessage()
                    );

                } catch (InterruptedException e) {

                    System.out.println(
                        "Subscrição interrompida p" + k
                    );

                    Thread.currentThread().interrupt();
                }

            }, "alarme-p" + k);

            t.start();

            System.out.println(
                "Thread lançada para o tópico "
                + Wire.topicData(k)
            );
        }
    }
}
