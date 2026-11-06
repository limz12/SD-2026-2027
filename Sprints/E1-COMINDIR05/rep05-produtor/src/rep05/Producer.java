package rep05;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Random;
import java.util.Scanner;

/**
 * Produtor: lê leituras da CLI, calcula a partição do sensor e publica a
 * leitura no tópico de
 * ENTRADA dessa partição (rep04-in-p<k>). Não conhece os líderes: publica no
 * tópico e pronto.
 *
 * Uso: java -cp out rep04.Producer <N> [api]
 * N = número de partições (tem de ser igual ao dos líderes; N=1 é a Parte A)
 *
 * Comandos: <sensor> <temperatura> | rajada <n> <s> | rajada-quente <n> <s> |
 * sair
 *
 * Detetor de falhas (COMINDIR04): o produtor subscreve os LOTES de todas as
 * partições, interpreta SÓ o
 * cabeçalho (inc, hb) e mantém um HeartbeatMonitor por partição (eventos em
 * monitor-produtor.txt).
 * Antes de enviar um IN para a partição k, se o líder de k estiver SUSPEITO,
 * avisa e envia na mesma:
 * não retém nem reenvia (fora do âmbito), por isso a leitura pode perder-se sem
 * deixar vestígio. Numa rajada
 * escreve-se o aviso uma vez por partição e, no fim, o total de avisos.
 *
 * Carimbos: o produtor não observa registo algum, por isso envia SEMPRE L = 0 e
 * V = (0,...,0).
 * Quem cria dependências causais entre partições é o processador de alarmes,
 * não o produtor.
 *
 * Troço produtor -> líder: NÃO tem numeração. Se o líder da partição estiver
 * parado, o publish
 * devolve 200 na mesma e a leitura perde-se sem que o produtor, ou ninguém, dê
 * conta disso.
 */
public class Producer {
    static IpfsPubSub ipfs;
    static Partitioner part;
    static final Random RND = new Random();
    static VectorClock zero; // (0,...,0) com dimensão N: nunca é alterado, só serializado
    static HeartbeatMonitor[] monitores;

    static synchronized void evento(String linha) {
        String l = System.currentTimeMillis() + ";" + linha;
        try {
            Files.writeString(Paths.get("monitor-produtor.txt"), l + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("ERRO a escrever o ficheiro de eventos: " + e.getMessage());
        }
        if (!linha.startsWith("RECEBIDO"))
            System.out.println("\n[monitor] " + l);
    }

    static void subscrever(int k) {
        try {
            ipfs.subscribe(Wire.topicData(k), msg -> { // BLOQUEIA aqui: a resposta HTTP nunca termina
                try {
                    Batch b = Batch.decodeHeader(msg); // só o cabeçalho: o produtor não precisa dos registos
                    monitores[k].onHeartbeat(b.inc, b.hb);
                    evento("RECEBIDO;p" + k + ";inc=" + b.inc + ",hb=" + b.hb + ",ultimoSeq=" + b.lastSeq
                            + ",n=" + Batch.headerCount(msg) + ",seq=-");
                } catch (IllegalArgumentException e) {
                    System.out.println("\n[p" + k + "] LOTE ilegível: " + e.getMessage());
                }
            });
        } catch (IOException | InterruptedException e) {
            System.out.println(
                    "\n[p" + k + "] subscrição terminada: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // publica uma leitura no tópico de entrada da partição do sensor; devolve a
    // partição
    static int enviar(String sensor, double temp, int[] avisos) throws IOException, InterruptedException {
        int k = part.of(sensor);
        if (monitores[k].state() == HeartbeatMonitor.State.SUSPEITO) {
            // assinala e envia na mesma: sem retenção nem reenvio, a leitura pode perder-se
            if (avisos == null || avisos[k]++ == 0) {
                System.out.println("AVISO: líder p" + k + " suspeito; leitura " + sensor + " pode perder-se");
            }
        }
        ipfs.publish(Wire.topicIn(k), Wire.in(sensor, temp, 0, zero));
        return k;
    }

    // rajada: sensores uniformes em S01..S<s>.
    // rajada-quente: 80 % das leituras vêm de S01; as restantes uniformes em
    // S02..S<s>.
    static String escolherSensor(int s, boolean quente) {
        if (quente && (s < 2 || RND.nextDouble() < 0.8)) {
            return "S01";
        }
        int m = quente ? 2 + RND.nextInt(s - 1) : 1 + RND.nextInt(s);
        return String.format("S%02d", m);
    }

    static void rajada(int n, int s, boolean quente) throws InterruptedException {
        int[] porParticao = new int[part.count()];
        int[] avisos = new int[part.count()];
        long primeiro = 0, ultimo = 0;
        int falhadas = 0;
        String primeiroErro = null;
        for (int i = 0; i < n; i++) {
            String sensor = escolherSensor(s, quente);
            double t = Math.round((15 + RND.nextDouble() * 15) * 10) / 10.0;
            if (i == 0)
                primeiro = System.currentTimeMillis();
            try {
                porParticao[enviar(sensor, t, avisos)]++;
            } catch (IOException e) {
                // O Kubo recusa, por vezes, um publish isolado (ex.: HTTP 500 "validation
                // ignored").
                // A leitura NÃO chegou ao tópico e não há numeração neste troço: é o produtor,
                // e só ele,
                // que sabe — por isso conta-se e assinala-se, em vez de abortar a rajada.
                falhadas++;
                if (primeiroErro == null)
                    primeiroErro = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            ultimo = System.currentTimeMillis();
        }
        long dur = Math.max(1, ultimo - primeiro);
        System.out.println((quente ? "Rajada quente" : "Rajada") + " de " + n + " leituras (sensores S01..S"
                + String.format("%02d", s) + ")");
        System.out.println("  primeira publicação em " + primeiro + " ms | última em " + ultimo + " ms");
        System.out.println("  tempo de envio = " + dur + " ms | débito = " + (n * 1000L / dur) + " leituras/s");
        StringBuilder sb = new StringBuilder("  leituras por partição:");
        for (int k = 0; k < porParticao.length; k++) {
            sb.append(" p").append(k).append("=").append(porParticao[k]);
        }
        System.out.println(sb);
        StringBuilder av = new StringBuilder();
        for (int k = 0; k < avisos.length; k++)
            if (avisos[k] > 0)
                av.append(" p").append(k).append("=").append(avisos[k]);
        if (av.length() > 0)
            System.out.println("  AVISOS (líder suspeito, leituras enviadas na mesma):" + av);
        if (falhadas > 0) {
            System.out.println(
                    "  PUBLICAÇÕES FALHADAS = " + falhadas + " (leituras perdidas) | primeira: " + primeiroErro);
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: Producer <N> [api]");
            return;
        }
        try {
            part = new Partitioner(Integer.parseInt(args[0]));
        } catch (IllegalArgumentException e) { // inclui NumberFormatException
            System.out.println("N inválido: " + args[0]);
            return;
        }
        zero = new VectorClock(part.count());
        monitores = new HeartbeatMonitor[part.count()];
        for (int k = 0; k < part.count(); k++) {
            monitores[k] = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, Producer::evento);
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        ipfs = new IpfsPubSub(api);
        System.out.println("Produtor pronto (N = " + part.count() + ", API " + api + ", Δ=" + Wire.PERIODO_HB_MS
                + " ms, timeout=" + Wire.PERIODO_HB_MS * Wire.FATOR_TIMEOUT + " ms).");
        for (int k = 0; k < part.count(); k++) {
            final int kk = k;
            Thread t = new Thread(() -> subscrever(kk), "sub-p" + kk);
            t.setDaemon(true);
            t.start();
        }
        Thread verificacao = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Wire.VERIFICACAO_MS);
                } catch (InterruptedException e) {
                    return;
                }
                for (HeartbeatMonitor m : monitores)
                    m.check();
            }
        }, "verificacao");
        verificacao.setDaemon(true);
        verificacao.start();
        System.out.println("Comandos: <sensor> <temperatura> | rajada <n> <s> | rajada-quente <n> <s> | sair");

        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine())
                    break; // BLOQUEIA à espera do utilizador (CLI)
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair"))
                    break;
                String[] p = line.split("\\s+");

                if (p[0].equalsIgnoreCase("rajada") || p[0].equalsIgnoreCase("rajada-quente")) {
                    int n, s;
                    try {
                        n = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);
                        if (n < 1 || s < 1)
                            throw new NumberFormatException();
                    } catch (RuntimeException e) { // argumentos em falta ou inválidos
                        System.out.println("Uso: " + p[0] + " <n> <s>");
                        continue;
                    }
                    rajada(n, s, p[0].equalsIgnoreCase("rajada-quente"));
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                try {
                    System.out.println("→ p" + enviar(p[0], temp, null));
                } catch (IOException e) {
                    // Daemon IPFS parado: ConnectException (a mensagem vem vazia no HttpClient).
                    System.out.println("FALHA A PUBLICAR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}