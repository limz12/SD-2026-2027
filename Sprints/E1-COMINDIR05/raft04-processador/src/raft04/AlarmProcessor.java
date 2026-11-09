
package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Processador de alarmes da COMINDIR05.
 *
 * Execução:
 * AlarmProcessor <N> [api]
 *
 * Subscreve os tópicos LOTE de todas as partições.
 *
 * Apenas processa lotes cujo termo seja igual
 * ou superior ao maior termo já observado
 * na respetiva partição.
 *
 * Cada registo é identificado pelo par
 * (seq, termo).
 *
 * Mantém os relógios causais da COMINDIR03
 * e um HeartbeatMonitor por partição.
 *
 * Antes de publicar um alarme, verifica
 * se a partição de destino está SUSPEITA.
 *
 * Mesmo havendo suspeita, publica o alarme.
 *
 * Eventos:
 * monitor-processador.txt
 */
public class AlarmProcessor {

    static final boolean SILENCIOSO =
            Boolean.getBoolean("silencioso");

    // Protege os relógios e as estatísticas globais.
    static final Object LOCK = new Object();

    // Serializa o processamento dos lotes
    // de uma mesma partição.
    static Object[] locksParticao;

    // Relógios do processador.
    static long lp;
    static VectorClock vp;

    // Duplicados: cada chave representa (seq, termo).
    static List<Set<String>> vistos;

    // Maior termo observado em cada partição.
    static long[] maiorTermo;

    // Um monitor por partição.
    static HeartbeatMonitor[] monitores;

    // Estatísticas desde o último resumo.
    static int registos;
    static int alarmes;
    static int ignoradosAlarm;
    static int duplicados;
    static int rejeitados;
    static int falhas;
    static int avisos;

    static long ultimoMs;

    /**
     * Apresenta informação normal na consola.
     */
    static void falar(String s) {

        if (!SILENCIOSO) {
            System.out.println(s);
        }
    }

    /**
     * Escreve um evento na consola e em
     * monitor-processador.txt.
     *
     * O instante é obtido através de
     * System.currentTimeMillis().
     *
     * A deteção de silêncio fica a cargo
     * do HeartbeatMonitor.
     */
    static synchronized void evento(String linha) {

        String l =
                System.currentTimeMillis()
                + ";" + linha;

        try {

            Files.writeString(
                Paths.get("monitor-processador.txt"),
                l + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );

        } catch (IOException e) {

            System.err.println(
                "ERRO a escrever o ficheiro de eventos: "
                + e.getMessage()
            );
        }

        if (!SILENCIOSO
                || !linha.startsWith("RECEBIDO")) {

            System.out.println("[monitor] " + l);
        }
    }

    /**
     * Cria uma chave para o par (seq, termo).
     *
     * A mesma sequência em termos diferentes
     * representa registos diferentes.
     */
    static String chaveRegisto(
            SensorRecord r) {

        return r.getSeq() + ";" + r.getTerm();
    }

    /**
     * Processa um LOTE recebido por PubSub.
     *
     * Regras:
     *
     * 1. Descodificar o lote.
     * 2. Ignorar termos inferiores.
     * 3. Atualizar o maior termo observado.
     * 4. Comunicar o heartbeat ao monitor.
     * 5. Assinalar RECEBIDO.
     * 6. Processar os registos pela ordem.
     */
    static void processarLote(
            int k,
            int n,
            Partitioner part,
            IpfsPubSub ps,
            String msg) {

        Batch b;

        try {

            b = Batch.decode(msg, n);

        } catch (IllegalArgumentException e) {

            synchronized (LOCK) {
                rejeitados++;
                ultimoMs = System.currentTimeMillis();
            }

            String cabecalho = msg == null
                    ? "null"
                    : msg.split("\\R", 2)[0];

            System.out.println(
                "[p" + k + "] REJEITADO: "
                + cabecalho
                + " (" + e.getMessage() + ")"
            );

            return;
        }

        /*
         * O processamento dos lotes de cada partição
         * é serializado para impedir que um lote antigo
         * seja processado depois de um termo superior.
         *
         * As outras partições continuam independentes.
         */
        synchronized (locksParticao[k]) {

            // Rejeitar lotes de termos inferiores.
            if (b.term < maiorTermo[k]) {

                evento(
                    "IGNORADO;p" + k
                    + ";motivo=termo_antigo"
                    + ";termo=" + b.term
                    + ";maiorTermo=" + maiorTermo[k]
                    + ";lider=n" + b.leader
                    + ";hb=" + b.hb
                );

                return;
            }

            // Registar um termo superior.
            if (b.term > maiorTermo[k]) {
                maiorTermo[k] = b.term;
            }

            /*
             * O HeartbeatMonitor mantém o termo,
             * o líder, o número de heartbeat
             * e o estado de suspeita.
             *
             * A chamada é feita também para
             * lotes vazios (n = 0).
             */
            monitores[k].onHeartbeat(
                b.term,
                b.leader,
                b.hb
            );

            // Calcular o intervalo transportado.
            String faixa;

            if (b.records.isEmpty()) {

                faixa = "-";

            } else {

                long minimo = Long.MAX_VALUE;
                long maximo = Long.MIN_VALUE;

                for (SensorRecord r : b.records) {

                    minimo = Math.min(
                        minimo,
                        r.getSeq()
                    );

                    maximo = Math.max(
                        maximo,
                        r.getSeq()
                    );
                }

                faixa = minimo + ".." + maximo;
            }

            // Assinalar receção do lote.
            evento(
                "RECEBIDO;p" + k
                + ";termo=" + b.term
                + ";lider=n" + b.leader
                + ";hb=" + b.hb
                + ";ultimoSeq=" + b.lastSeq
                + ";ultimoTermo=" + b.lastTerm
                + ";n=" + b.records.size()
                + ";seq=" + faixa
            );

            // Processar cada registo pela ordem do lote.
            for (SensorRecord r : b.records) {

                processarRegisto(
                    k,
                    part,
                    ps,
                    r
                );

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        }
    }

    /**
     * Processa um registo do lote.
     *
     * Regras da COMINDIR03:
     *
     * - Ignorar registos ALARM-.
     * - Ignorar pares (seq, termo) já tratados.
     * - Acumular os máximos de L e V.
     * - Gerar alarme quando temperatura >= limiar.
     *
     * A publicação decorre fora do LOCK global.
     */
    static void processarRegisto(
            int k,
            Partitioner part,
            IpfsPubSub ps,
            SensorRecord r) {

        // Os alarmes não originam outros alarmes
        // nem alteram os relógios do processador.
        if (r.getSensorId().startsWith("ALARM-")) {

            synchronized (LOCK) {
                ignoradosAlarm++;
            }

            return;
        }

        boolean geraAlarme =
                r.getTemperature() >= Wire.LIMIAR_ALARME;

        long lSnap = 0;
        VectorClock vSnap = null;

        synchronized (LOCK) {

            // Duplicados por (seq, termo), não por seq.
            String chave = chaveRegisto(r);

            if (!vistos.get(k).add(chave)) {

                duplicados++;
                return;
            }

            registos++;
            ultimoMs = System.currentTimeMillis();

            // Acumular relógio de Lamport.
            lp = Math.max(
                lp,
                r.getLamport()
            );

            // Acumular relógio vetorial.
            vp.merge(r.getVector());

            // Guardar instantâneo consistente
            // para o eventual alarme.
            if (geraAlarme) {

                lSnap = lp;
                vSnap = vp.copy();
            }
        }

        if (!geraAlarme) {
            return;
        }

        // Construir sensor de alarme.
        String sensorAlarme =
                "ALARM-" + r.getSensorId();

        // Partição de destino.
        int j = part.of(sensorAlarme);

        /*
         * Verificar se existe suspeita
         * relativamente ao líder de destino.
         *
         * A suspeita não bloqueia o envio.
         */
        if (monitores[j].state()
                == HeartbeatMonitor.State.SUSPEITO) {

            synchronized (LOCK) {
                avisos++;
            }

            evento(
                "AVISO: partição p" + j
                + " sem líder conhecido; alarme "
                + sensorAlarme
                + " pode perder-se"
            );
        }

        // Construir mensagem IN com os relógios
        // observados pelo processador.
        String mensagem = Wire.in(
            sensorAlarme,
            r.getTemperature(),
            lSnap,
            vSnap
        );

        try {

            // A publicação é feita mesmo que
            // o monitor esteja SUSPEITO.
            ps.publish(
                Wire.topicIn(j),
                mensagem
            );

            synchronized (LOCK) {
                alarmes++;
            }

            falar(
                "ALARME: "
                + r.getSensorId()
                + " p" + k + "#" + r.getSeq()
                + " termo=" + r.getTerm()
                + " → p" + j
                + " (L=" + lSnap
                + " V=" + vSnap + ")"
            );

        } catch (IOException e) {

            synchronized (LOCK) {
                falhas++;
            }

            System.out.println(
                "FALHA A PUBLICAR ALARME: "
                + e.getClass().getSimpleName()
                + ": " + e.getMessage()
            );

        } catch (InterruptedException e) {

            synchronized (LOCK) {
                falhas++;
            }

            System.out.println(
                "Publicação do alarme interrompida: "
                + e.getMessage()
            );

            Thread.currentThread().interrupt();
        }
    }

    /**
     * Subscreve os lotes de uma partição.
     */
    static void subscrever(
            int k,
            int n,
            Partitioner part,
            IpfsPubSub ps) {

        try {

            ps.subscribe(
                Wire.topicData(k),
                msg -> processarLote(
                    k,
                    n,
                    part,
                    ps,
                    msg
                )
            );

            System.out.println(
                "[p" + k + "] subscrição terminada"
            );

        } catch (IOException e) {

            System.out.println(
                "[p" + k + "] IO: "
                + e.getClass().getSimpleName()
                + ": " + e.getMessage()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            System.out.println(
                "[p" + k + "] subscrição interrompida"
            );
        }
    }

    /**
     * Verifica periodicamente todos
     * os monitores das partições.
     *
     * O HeartbeatMonitor mede o silêncio
     * com System.nanoTime().
     */
    static void verificarHeartbeats() {

        while (!Thread.currentThread().isInterrupted()) {

            try {

                Thread.sleep(
                    Wire.VERIFICACAO_MS
                );

                for (HeartbeatMonitor m : monitores) {
                    m.check();
                }

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Apresenta um resumo após dois segundos
     * sem novos registos ou rejeições.
     */
    static void resumos() {

        while (!Thread.currentThread().isInterrupted()) {

            try {

                Thread.sleep(500);

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
                return;
            }

            synchronized (LOCK) {

                if ((registos > 0 || rejeitados > 0)
                        && System.currentTimeMillis()
                        - ultimoMs >= 2000) {

                    System.out.println(
                        "--- RESUMO processador --- "
                        + "registos=" + registos
                        + " | alarmes=" + alarmes
                        + " | ignorados(ALARM-)="
                        + ignoradosAlarm
                        + " | duplicados=" + duplicados
                        + " | rejeitados=" + rejeitados
                        + " | falhas=" + falhas
                        + " | avisos(partição suspeita)="
                        + avisos
                        + " | Lp=" + lp
                        + " | Vp=" + vp
                    );

                    registos = 0;
                    alarmes = 0;
                    ignoradosAlarm = 0;
                    duplicados = 0;
                    rejeitados = 0;
                    falhas = 0;
                    avisos = 0;
                }
            }
        }
    }

    /**
     * Execução:
     * AlarmProcessor <N> [api]
     */
    public static void main(String[] args) {

        if (args.length < 1 || args.length > 2) {

            System.out.println(
                "Uso: AlarmProcessor <N> [api]"
            );

            return;
        }

        int n;
        Partitioner part;

        try {

            n = Integer.parseInt(args[0]);

            if (n <= 0) {
                throw new IllegalArgumentException();
            }

            part = new Partitioner(n);

        } catch (IllegalArgumentException e) {

            System.out.println(
                "N inválido: " + args[0]
            );

            return;
        }

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

        // Inicializar relógios.
        lp = 0;
        vp = new VectorClock(n);

        // Inicializar estruturas por partição.
        vistos = new ArrayList<>();

        maiorTermo = new long[n];
        monitores = new HeartbeatMonitor[n];
        locksParticao = new Object[n];

        for (int k = 0; k < n; k++) {

            vistos.add(new HashSet<>());

            // Ainda não foi observado nenhum termo.
            maiorTermo[k] = -1;

            locksParticao[k] = new Object();

            monitores[k] = new HeartbeatMonitor(
                k,
                Wire.PERIODO_HB_MS,
                Wire.FATOR_TIMEOUT,
                AlarmProcessor::evento
            );
        }

        System.out.println(
            "Processador de alarmes raft04 pronto."
        );

        System.out.println(
            "N=" + n
            + " | limiar=" + Wire.LIMIAR_ALARME
            + " °C"
            + " | período=" + Wire.PERIODO_HB_MS
            + " ms"
            + " | timeout="
            + Wire.PERIODO_HB_MS
                * Wire.FATOR_TIMEOUT
            + " ms"
            + " | API=" + api
        );

        // Uma subscrição para cada partição.
        for (int k = 0; k < n; k++) {

            final int kk = k;

            Thread t = new Thread(
                () -> subscrever(
                    kk,
                    n,
                    part,
                    ps
                ),
                "sub-p" + kk
            );

            t.start();
        }

        // Verificação periódica dos heartbeats.
        Thread verificacao = new Thread(
            AlarmProcessor::verificarHeartbeats,
            "verificacao"
        );

        verificacao.setDaemon(true);
        verificacao.start();

        // Resumos de processamento.
        Thread monitor = new Thread(
            AlarmProcessor::resumos,
            "resumos"
        );

        monitor.setDaemon(true);
        monitor.start();
    }
}
