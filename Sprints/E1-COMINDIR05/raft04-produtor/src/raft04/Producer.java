
package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.Scanner;

/**
 * Produtor de leituras da COMINDIR05.
 *
 * Uso:
 * java raft04.Producer <N> [api]
 *
 * Comandos:
 * <sensor> <temperatura>
 * rajada <n> <s>
 * rajada-quente <n> <s>
 * sair
 *
 * Publica mensagens IN no tópico de entrada
 * da partição correspondente ao sensor.
 *
 * IN;sensor;temperatura;L;V
 *
 * Como o produtor não observa registos,
 * utiliza sempre L=0 e V=(0,...,0).
 *
 * Subscreve os LOTES de todas as partições
 * para acompanhar os líderes através de
 * um HeartbeatMonitor por partição.
 *
 * Antes de publicar uma leitura, verifica
 * se a partição de destino está SUSPEITA.
 *
 * Mesmo havendo suspeita, publica o IN.
 *
 * Ficheiros:
 *
 * produtor.txt
 *   Uma linha por cada IN publicado com sucesso.
 *
 * monitor-produtor.txt
 *   Eventos dos monitores de heartbeat.
 */
public class Producer {

    static IpfsPubSub ipfs;
    static Partitioner part;

    static final Random RND = new Random();

    // Relógio vetorial nulo com dimensão N.
    static VectorClock zero;

    // Um monitor por partição.
    static HeartbeatMonitor[] monitores;

    // Protege a escrita em produtor.txt.
    static final Object LOCK_PRODUTOR = new Object();

    /**
     * Regista eventos do detetor de falhas.
     *
     * Formato:
     * instante;evento;p<k>;detalhes
     */
    static synchronized void evento(String linha) {

        String l =
            System.currentTimeMillis() + ";" + linha;

        try {

            Files.writeString(
                Paths.get("monitor-produtor.txt"),
                l + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );

        } catch (IOException e) {

            System.out.println(
                "ERRO a escrever o ficheiro de eventos: "
                + e.getMessage()
            );
        }

        if (!linha.startsWith("RECEBIDO")) {

            System.out.println(
                "\n[monitor] " + l
            );
        }
    }

    /**
     * Regista em produtor.txt um IN
     * cuja publicação terminou com sucesso.
     *
     * Formato utilizado:
     *
     * instante;p<k>;sensor;temperatura
     *
     * O instante é System.currentTimeMillis().
     *
     * É chamado apenas depois de ipfs.publish()
     * terminar com sucesso.
     */
    static void registarEnvio(
            String sensor,
            double temperatura,
            int k) {

        String linha =
            System.currentTimeMillis()
            + ";p" + k + ";"
            + sensor + ";"
            + temperatura;

        synchronized (LOCK_PRODUTOR) {

            try {

                Files.writeString(
                    Paths.get("produtor.txt"),
                    linha + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );

            } catch (IOException e) {

                System.out.println(
                    "ERRO a escrever produtor.txt: "
                    + e.getMessage()
                );
            }
        }
    }

    /**
     * Subscreve os LOTES de uma partição.
     *
     * O produtor apenas interpreta o cabeçalho:
     *
     * LOTE;term;leader;address;port;hb;lastSeq;lastTerm;n
     *
     * Não precisa de reconstruir os registos.
     */
    static void subscrever(int k) {

        try {

            ipfs.subscribe(
                Wire.topicData(k),
                msg -> {

                    try {

                        Batch b =
                            Batch.decodeHeader(msg);

                        // Atualizar o monitor com
                        // termo, líder e heartbeat.
                        monitores[k].onHeartbeat(
                            b.term,
                            b.leader,
                            b.hb
                        );

                        evento(
                            "RECEBIDO;p" + k
                            + ";termo=" + b.term
                            + ";lider=n" + b.leader
                            + ";hb=" + b.hb
                            + ";ultimoSeq=" + b.lastSeq
                            + ";ultimoTermo=" + b.lastTerm
                            + ";n=" + Batch.headerCount(msg)
                            + ";seq=-"
                        );

                    } catch (IllegalArgumentException e) {

                        System.out.println(
                            "\n[p" + k
                            + "] LOTE ilegível: "
                            + e.getMessage()
                        );
                    }
                }
            );

            System.out.println(
                "\n[p" + k
                + "] subscrição terminada"
            );

        } catch (IOException e) {

            System.out.println(
                "\n[p" + k
                + "] IO: "
                + e.getClass().getSimpleName()
                + ": " + e.getMessage()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            System.out.println(
                "\n[p" + k
                + "] subscrição interrompida"
            );
        }
    }

    /**
     * Publica uma leitura na partição do sensor.
     *
     * Devolve a partição utilizada.
     *
     * Se a partição estiver SUSPEITA:
     * - Emite o aviso exigido.
     * - Publica o IN na mesma.
     * - Não retém nem reenvia a leitura.
     *
     * avisos:
     * Contador opcional utilizado nas rajadas.
     */
    static int enviar(
            String sensor,
            double temp,
            int[] avisos)
            throws IOException, InterruptedException {

        int k = part.of(sensor);

        /*
         * Verificar o estado do monitor
         * antes de enviar o IN.
         *
         * A ausência de um líder conhecido
         * pode significar que a leitura
         * não será registada.
         *
         * A publicação não é bloqueada.
         */
        if (monitores[k].state()
                == HeartbeatMonitor.State.SUSPEITO) {

            if (avisos != null) {
                avisos[k]++;
            }

            System.out.println(
                "AVISO: partição p" + k
                + " sem líder conhecido; leitura "
                + sensor
                + " pode perder-se"
            );
        }

        // Criar mensagem IN.
        // O produtor envia sempre L=0 e V=0.
        String mensagem = Wire.in(
            sensor,
            temp,
            0,
            zero
        );

        /*
         * Publicar no tópico IN.
         *
         * Este tópico não é afetado
         * pelo isolamento dos nós Raft.
         */
        ipfs.publish(
            Wire.topicIn(k),
            mensagem
        );

        /*
         * Apenas registar como enviado depois
         * de a publicação terminar com sucesso.
         */
        registarEnvio(
            sensor,
            temp,
            k
        );

        return k;
    }

    /**
     * Escolhe um sensor.
     *
     * Rajada normal:
     * Distribuição uniforme em S01..S<s>.
     *
     * Rajada quente:
     * 80% das leituras usam S01.
     * 20% usam S02..S<s>.
     */
    static String escolherSensor(
            int s,
            boolean quente) {

        if (quente
                && RND.nextDouble() < 0.8) {

            return "S01";
        }

        int m;

        if (quente) {

            m = 2 + RND.nextInt(s - 1);

        } else {

            m = 1 + RND.nextInt(s);
        }

        return String.format("S%02d", m);
    }

    /**
     * Executa uma rajada de leituras.
     *
     * Todas as leituras passam por enviar(),
     * pelo que são abrangidas:
     *
     * - Pela verificação SUSPEITO.
     * - Pela publicação IN.
     * - Pelo registo em produtor.txt.
     */
    static void rajada(
            int n,
            int s,
            boolean quente)
            throws InterruptedException {

        int[] porParticao =
            new int[part.count()];

        int[] avisos =
            new int[part.count()];

        long primeiro = 0;
        long ultimo = 0;

        int falhadas = 0;

        String primeiroErro = null;

        for (int i = 0; i < n; i++) {

            String sensor =
                escolherSensor(s, quente);

            double t = Math.round(
                (15 + RND.nextDouble() * 15) * 10
            ) / 10.0;

            if (i == 0) {

                primeiro =
                    System.currentTimeMillis();
            }

            try {

                int k = enviar(
                    sensor,
                    t,
                    avisos
                );

                porParticao[k]++;

            } catch (IOException e) {

                /*
                 * Uma falha HTTP significa que
                 * a publicação não foi confirmada.
                 *
                 * Não se acrescenta uma linha
                 * a produtor.txt nesse caso.
                 */
                falhadas++;

                if (primeiroErro == null) {

                    primeiroErro =
                        e.getClass().getSimpleName()
                        + ": " + e.getMessage();
                }
            }

            ultimo = System.currentTimeMillis();
        }

        long dur =
            Math.max(1, ultimo - primeiro);

        System.out.println(
            (quente
                ? "Rajada quente"
                : "Rajada")
            + " de " + n
            + " leituras (sensores S01..S"
            + String.format("%02d", s)
            + ")"
        );

        System.out.println(
            "  primeira publicação em "
            + primeiro
            + " ms | última em "
            + ultimo
            + " ms"
        );

        System.out.println(
            "  tempo de envio = "
            + dur
            + " ms | débito = "
            + (n * 1000L / dur)
            + " leituras/s"
        );

        // Resumo por partição.
        StringBuilder sb =
            new StringBuilder(
                "  leituras por partição:"
            );

        for (int k = 0;
                k < porParticao.length;
                k++) {

            sb.append(" p")
              .append(k)
              .append("=")
              .append(porParticao[k]);
        }

        System.out.println(sb);

        // Resumo dos avisos.
        StringBuilder av =
            new StringBuilder();

        for (int k = 0;
                k < avisos.length;
                k++) {

            if (avisos[k] > 0) {

                av.append(" p")
                  .append(k)
                  .append("=")
                  .append(avisos[k]);
            }
        }

        if (av.length() > 0) {

            System.out.println(
                "  AVISOS (partição sem líder conhecido,"
                + " leituras enviadas na mesma):"
                + av
            );
        }

        if (falhadas > 0) {

            System.out.println(
                "  PUBLICAÇÕES FALHADAS = "
                + falhadas
                + " | primeira: "
                + primeiroErro
            );
        }
    }

    /**
     * Verifica periodicamente os monitores
     * de todas as partições.
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
                return;
            }
        }
    }

    /**
     * Inicialização do produtor.
     */
    public static void main(String[] args) {

        if (args.length < 1 || args.length > 2) {

            System.out.println(
                "Uso: Producer <N> [api]"
            );

            return;
        }

        try {

            part = new Partitioner(
                Integer.parseInt(args[0])
            );

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

        // O produtor nunca altera estes relógios.
        zero = new VectorClock(
            part.count()
        );

        /*
         * Criar um monitor por partição.
         *
         * HeartbeatMonitor é a versão raft04,
         * que utiliza (term, leader, hb).
         */
        monitores =
            new HeartbeatMonitor[part.count()];

        for (int k = 0;
                k < part.count();
                k++) {

            monitores[k] = new HeartbeatMonitor(
                k,
                Wire.PERIODO_HB_MS,
                Wire.FATOR_TIMEOUT,
                Producer::evento
            );
        }

        String api = args.length > 1
            ? args[1]
            : "http://127.0.0.1:5001/api/v0";

        ipfs = new IpfsPubSub(api);

        System.out.println(
            "Produtor raft04 pronto "
            + "(N = " + part.count()
            + ", API " + api
            + ", Δ=" + Wire.PERIODO_HB_MS
            + " ms, timeout="
            + Wire.PERIODO_HB_MS
                * Wire.FATOR_TIMEOUT
            + " ms)."
        );

        /*
         * Uma thread de subscrição
         * para cada partição.
         */
        for (int k = 0;
                k < part.count();
                k++) {

            final int kk = k;

            Thread t = new Thread(
                () -> subscrever(kk),
                "sub-p" + kk
            );

            t.setDaemon(true);
            t.start();
        }

        // Thread de verificação dos heartbeats.
        Thread verificacao = new Thread(
            Producer::verificarHeartbeats,
            "verificacao"
        );

        verificacao.setDaemon(true);
        verificacao.start();

        System.out.println(
            "Comandos: "
            + "<sensor> <temperatura>"
            + " | rajada <n> <s>"
            + " | rajada-quente <n> <s>"
            + " | sair"
        );

        /*
         * Interface interativa.
         */
        try (Scanner sc = new Scanner(System.in)) {

            while (true) {

                System.out.print("> ");

                if (!sc.hasNextLine()) {
                    break;
                }

                String line =
                    sc.nextLine().trim();

                if (line.isEmpty()) {
                    continue;
                }

                if (line.equalsIgnoreCase("sair")) {
                    break;
                }

                String[] p =
                    line.split("\\s+");

                /*
                 * Comandos de rajada.
                 */
                if (p[0].equalsIgnoreCase("rajada")
                        || p[0].equalsIgnoreCase(
                            "rajada-quente")) {

                    int n;
                    int s;

                    boolean quente =
                        p[0].equalsIgnoreCase(
                            "rajada-quente");

                    try {

                        if (p.length != 3) {
                            throw new NumberFormatException();
                        }

                        n = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);

                        if (n < 1 || s < 1) {
                            throw new NumberFormatException();
                        }

                        // A rajada quente precisa
                        // de pelo menos dois sensores.
                        if (quente && s < 2) {
                            throw new NumberFormatException();
                        }

                    } catch (NumberFormatException e) {

                        System.out.println(
                            "Uso: " + p[0]
                            + " <n> <s>"
                            + (quente
                                ? " (s >= 2)"
                                : "")
                        );

                        continue;
                    }

                    rajada(
                        n,
                        s,
                        quente
                    );

                    continue;
                }

                /*
                 * Leitura individual:
                 * <sensor> <temperatura>
                 */
                if (p.length != 2) {

                    System.out.println(
                        "Formato inválido."
                    );

                    continue;
                }

                double temp;

                try {

                    temp =
                        Double.parseDouble(p[1]);

                    if (!Double.isFinite(temp)) {
                        throw new NumberFormatException();
                    }

                } catch (NumberFormatException e) {

                    System.out.println(
                        "Temperatura inválida: "
                        + p[1]
                    );

                    continue;
                }

                try {

                    int k = enviar(
                        p[0],
                        temp,
                        null
                    );

                    System.out.println(
                        "→ p" + k
                    );

                } catch (IOException e) {

                    System.out.println(
                        "FALHA A PUBLICAR: "
                        + e.getClass().getSimpleName()
                        + ": " + e.getMessage()
                    );
                }
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }
}
