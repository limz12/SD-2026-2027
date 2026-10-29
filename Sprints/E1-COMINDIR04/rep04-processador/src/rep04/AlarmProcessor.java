package rep04;

import java.io.FileWriter;
import java.io.IOException;
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
    static HeartbeatMonitor[] monitors;
    // Sequências já recebidas em cada partição
    static Set<Long>[] recebidos;

    /**
     * Processa uma mensagem REG recebida
     * de uma determinada partição.
     */
    static void logEvent(String line) {
        String out = System.currentTimeMillis() + ";" + line;
        System.out.println(out);
        try (FileWriter fw = new FileWriter("monitor-processador.txt", true)) { fw.write(out + "\n"); } catch (IOException ignored) {}
    }

    static void onLote(int k, String msg) {
        String[] lines = msg.trim().split("\n");
        String[] h = lines[0].trim().split(";");
        int n = Integer.parseInt(h[6]);
        Batch b = Batch.decode(msg, n);
        if (monitors[k] != null) {
            monitors[k].onHeartbeat(b.inc, b.hb);
            logEvent("RECEBIDO;p" + k + ";inc=" + b.inc + ";hb=" + b.hb + ";ultimoSeq=" + b.lastSeq + ";n=" + b.records.size());
        }
        for (SensorRecord r : b.records) {
            String sensor = r.getSensorId();
            long seq = r.getSeq();
            if (sensor.startsWith("ALARM-")) continue;

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
                return;
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

        // Criar a mensagem IN com os relógios
        String mensagem = Wire.in(
            sensorAlarme,
            r.getTemperature(),
            novoLp,
            novoVp
        );

        if (monitors[j] != null && monitors[j].state() == HeartbeatMonitor.State.SUSPEITO) {
            logEvent("AVISO: líder p" + j + " suspeito; alarme " + sensor + " pode perder-se");
        }
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
        monitors = new HeartbeatMonitor[N];

        for (int k = 0; k < N; k++) {
            recebidos[k] = new HashSet<>();
            final int kk = k;
            monitors[k] = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, msg -> logEvent(msg));
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

        // Uma thread de subscrição por partição
        for (int i = 0; i < N; i++) {

            final int k = i;

            Thread t = new Thread(() -> {

                try {

                    ps.subscribe(
                        Wire.topicData(k),
                        msg -> onLote(k, msg)                    );

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
        }
        Thread checker = new Thread(() -> {
            while (true) {
                for (int i=0;i<N;i++) if (monitors[i]!=null) monitors[i].check();
                try { Thread.sleep(Wire.VERIFICACAO_MS); } catch (InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        }, "checker");
        checker.setDaemon(true);
        checker.start();
    }
}
