package rep03;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

/**
 * Produtor: lê leituras da CLI, calcula a partição do sensor e publica a
 * leitura no tópico de
 * ENTRADA dessa partição (rep03-in-p<k>). Não conhece os líderes: publica no
 * tópico e pronto.
 *
 * Uso: java -cp out rep03.Producer <N> [api]
 * N = número de partições (tem de ser igual ao dos líderes; N=1 é a Parte A)
 *
 * Comandos: <sensor> <temperatura> | rajada <n> <s> | rajada-quente <n> <s> |
 * sair
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

    // publica uma leitura no tópico de entrada da partição do sensor; devolve a
    // partição
    static int enviar(String sensor, double temp) throws IOException, InterruptedException {
        int k = part.of(sensor);
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
        long primeiro = 0, ultimo = 0;
        int falhadas = 0;
        String primeiroErro = null;
        for (int i = 0; i < n; i++) {
            String sensor = escolherSensor(s, quente);
            double t = Math.round((15 + RND.nextDouble() * 15) * 10) / 10.0;
            if (i == 0)
                primeiro = System.currentTimeMillis();
            try {
                porParticao[enviar(sensor, t)]++;
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
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        ipfs = new IpfsPubSub(api);
        System.out.println("Produtor pronto (N = " + part.count() + ", API " + api + ").");
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
                    System.out.println("→ p" + enviar(p[0], temp));
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