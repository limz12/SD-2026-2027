
package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

public class AlarmProcessor {

    // Estado partilhado entre todas as threads
    static final Object LOCK = new Object();
    static final Object LOCK_LOG = new Object();

    static long Lp;
    static VectorClock Vp;

    static int N;
    static Partitioner partitioner;
    static IpfsPubSub ps;

    // Sequências já recebidas em cada partição
    static Set<Long>[] recebidos;

    // Um monitor de heartbeat por partição
    static HeartbeatMonitor[] monitores;

    // Ficheiro de eventos do processador
    static final Path ficheiroMonitor =
            Paths.get("monitor-processador.txt");

    /**
     * Escreve um evento na consola e no ficheiro.
     *
     * Formato:
     * instante;evento
     *
     * A medição do silêncio é feita pelo
     * HeartbeatMonitor com System.nanoTime().
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
     * Processa um registo recebido num lote.
     *
     * Regras da COMINDIR03:
     * - Ignorar sensores ALARM-.
     * - Ignorar duplicados por partição e seq.
     * - Atualizar Lp e Vp com os máximos.
     * - Gerar alarmes para temperaturas >= limiar.
     */
    static void processarRegisto(int k, SensorRecord r) {

        String sensor = r.getSensorId();
        long seq = r.getSeq();

        // Ignorar alarmes antes de atualizar
        // os relógios lógicos do processador
        if (sensor.startsWith("ALARM-")) {
            return;
        }

        long novoLp;
        VectorClock novoVp;

        // SECÇÃO SINCRONIZADA
        synchronized (LOCK) {

            // Ignorar registos duplicados
            // da mesma partição
            if (recebidos[k].contains(seq)) {

                registar(
                    "DUPLICADO;p" + k
                    + ";seq=" + seq
                );
                return;
            }

            // Marcar o registo como recebido
            recebidos[k].add(seq);

            // Atualizar o relógio de Lamport
            Lp = Math.max(
                Lp,
                r.getLamport()
            );

            // Incorporar o vetor do registo
            Vp.merge(r.getVector());

            // Guardar uma cópia consistente
            // dos relógios para o eventual alarme
            novoLp = Lp;
            novoVp = Vp.copy();
        }

        // Verificar o limiar de temperatura
        if (r.getTemperature() < Wire.LIMIAR_ALARME) {
            return;
        }

        // Identificador do novo alarme
        String sensorAlarme = "ALARM-" + sensor;

        // Calcular a partição de destino
        int j = partitioner.of(sensorAlarme);

        // CORREÇÃO:
        // Consultar o estado através de state(),
        // que devolve um valor do enum State.
        //
        // Se o líder estiver SUSPEITO, assinalar
        // o aviso, mas continuar a publicação.
        if (monitores[j].state()
                == HeartbeatMonitor.State.SUSPEITO) {

            registar(
                "AVISO: líder p" + j
                + " suspeito; alarme "
                + sensor
                + " pode perder-se"
            );
        }

        // Construir mensagem IN com os
        // relógios acumulados do processador
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

            registar(
                "ALARME: " + sensor
                + " p" + k + "#" + seq
                + " → p" + j
            );

        } catch (IOException e) {

            registar(
                "FALHA_ALARME;p" + j
                + ";sensor=" + sensor
                + ";erro=" + e.getMessage()
            );

        } catch (InterruptedException e) {

            registar(
                "ALARME_INTERROMPIDO;p" + j
                + ";sensor=" + sensor
            );

            Thread.currentThread().interrupt();
        }
    }

    /**
     * Processa uma mensagem LOTE recebida
     * de uma determinada partição.
     *
     * Formato:
     * LOTE;endereco;porto;inc;hb;ultimoSeq;n
     * registo1
     * registo2
     * ...
     */
    static void onLote(int k, String msg) {

        Batch lote;

        try {

            // Descodificar o lote completo
            lote = Batch.decode(msg, N);

        } catch (IllegalArgumentException e) {

            registar(
                "REJEITADO;p" + k
                + ";erro=" + e.getMessage()
            );
            return;
        }

        // Comunicar o heartbeat ao monitor,
        // mesmo quando o lote está vazio
        monitores[k].onHeartbeat(
            lote.inc,
            lote.hb
        );

        // Determinar o intervalo de sequências
        String intervalo;

        if (lote.records.isEmpty()) {

            intervalo = "vazio";

        } else {

            long minimo = Long.MAX_VALUE;
            long maximo = Long.MIN_VALUE;

            for (SensorRecord r : lote.records) {

                minimo = Math.min(
                    minimo, r.getSeq()
                );

                maximo = Math.max(
                    maximo, r.getSeq()
                );
            }

            intervalo = minimo + ".." + maximo;
        }

        // Assinalar a receção do lote
        registar(
            "RECEBIDO;p" + k
            + ";inc=" + lote.inc
            + ";hb=" + lote.hb
            + ";ultimoSeq=" + lote.lastSeq
            + ";n=" + lote.records.size()
            + ";intervalo=" + intervalo
        );

        // Processar todos os registos
        // pela ordem em que surgem no lote
        for (SensorRecord r : lote.records) {

            processarRegisto(k, r);
        }
    }

    /**
     * Arranque do processador de alarmes.
     *
     * Argumentos:
     * AlarmProcessor <N> [api]
     */
    public static void main(String[] args) {

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
                    "Erro: N deve ser positivo."
                );
                return;
            }

        } catch (NumberFormatException e) {

            System.out.println(
                "Erro: N deve ser um inteiro positivo."
            );
            return;
        }

        // Validar parâmetros de heartbeat
        if (Wire.PERIODO_HB_MS <= 0
                || Wire.FATOR_TIMEOUT <= 0
                || Wire.VERIFICACAO_MS <= 0) {

            System.out.println(
                "Erro: parâmetros de heartbeat inválidos."
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

        // Inicializar os monitores das partições
        monitores = new HeartbeatMonitor[N];

        for (int k = 0; k < N; k++) {

            recebidos[k] = new HashSet<>();

            // Cada partição tem o seu monitor
            // independente, com o mesmo logger
            monitores[k] = new HeartbeatMonitor(
                k,
                Wire.PERIODO_HB_MS,
                Wire.FATOR_TIMEOUT,
                AlarmProcessor::registar
            );
        }

        registar(
            "PROCESSADOR_INICIADO"
            + ";particoes=" + N
            + ";api=" + api
        );

        registar(
            "CONFIGURACAO"
            + ";limiar=" + Wire.LIMIAR_ALARME
            + ";periodo=" + Wire.PERIODO_HB_MS
            + ";fator=" + Wire.FATOR_TIMEOUT
            + ";verificacao=" + Wire.VERIFICACAO_MS
        );

        registar(
            "RELOGIOS_INICIAIS"
            + ";Lp=" + Lp
            + ";Vp=" + Vp
        );

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

            }, "alarme-p" + k);

            t.start();

            registar(
                "SUBSCRICAO;p" + k
                + ";topic=" + Wire.topicData(k)
            );
        }

        // Thread de verificação periódica
        // dos monitores de heartbeat
        Thread verificacao = new Thread(() -> {

            while (!Thread.currentThread().isInterrupted()) {

                try {

                    Thread.sleep(
                        Wire.VERIFICACAO_MS
                    );

                    // Verificar todas as partições
                    for (int k = 0; k < N; k++) {

                        monitores[k].check();
                    }

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                }
            }

        }, "verificacao-heartbeats");

        verificacao.setDaemon(true);
        verificacao.start();

        registar(
            "MONITORIZACAO_INICIADA"
        );
    }
}
