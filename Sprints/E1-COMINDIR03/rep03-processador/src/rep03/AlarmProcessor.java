
package rep03;

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

    // Sequências já recebidas em cada partição
    static Set<Long>[] recebidos;

    /**
     * Processa uma mensagem REG recebida
     * de uma determinada partição.
     */
    static void onReg(int k, String msg) {

        SensorRecord r;

        try {

            // Verificar o tipo da mensagem
            if (!Wire.type(msg).equals("REG")) {

                System.out.println(
                    "REJEITADO: p" + k + " " + msg
                );
                return;
            }

            // Separar host, porto e registo
            String[] partes = Wire.regParts(msg);

            // Reconstruir o registo com os relógios
            r = SensorRecord.fromLine(partes[2], N);

        } catch (IllegalArgumentException e) {

            System.out.println(
                "REJEITADO: p" + k + " " + msg
            );
            return;
        }

        String sensor = r.getSensorId();
        long seq = r.getSeq();

        // Ignorar registos de alarmes antes
        // de atualizar os relógios do processador
        if (sensor.startsWith("ALARM-")) {
            return;
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
            return;
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
                        msg -> onReg(k, msg)
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
