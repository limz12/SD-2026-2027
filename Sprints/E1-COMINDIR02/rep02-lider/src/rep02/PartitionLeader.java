package rep02;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Líder da partição k. Duas atividades bloqueantes em simultâneo:
 *
 * ENTRADA (thread "entrada") subscreve rep02-in-p<k> e fica bloqueada em
 * subscribe(), à espera
 * de cada IN. Atribui seq, escreve no ficheiro e publica REG.
 * RECUPERAÇÃO (thread principal) fica bloqueada em accept() e depois em
 * readLine(). Atende as
 * réplicas UMA DE CADA VEZ, lendo o ficheiro com range().
 *
 * Estado partilhado: o último seq atribuído ("seq", protegido por LOCK) e o
 * ficheiro (cujos métodos
 * são synchronized). A entrada ESCREVE o seq; a recuperação só o LÊ, para
 * limitar o "até" do pedido.
 *
 * Uso: java -cp out rep02.PartitionLeader <k> <N> [endereço=127.0.0.1] [api]
 * O endereço anunciado vem de argumento, e não de InetAddress.getLocalHost(),
 * porque o endereço
 * com que a MÁQUINA se vê (por exemplo, 127.0.1.1 ou um IP privado atrás de
 * NAT) não é
 * necessariamente o que as réplicas conseguem alcançar: quem sabe é quem
 * configura o sistema.
 *
 * Opção: -Dsilencioso=true (não escreve uma linha por registo; útil nas
 * rajadas).
 */
public class PartitionLeader {
    static final boolean SILENCIOSO = Boolean.getBoolean("silencioso");

    static final Object LOCK = new Object();
    static long seq; // último seq atribuído (estado partilhado, sob LOCK)

    // estatísticas da "rajada" corrente, sob LOCK
    static int contados, rejeitados;
    static long primeiroMs, ultimoMs;

    static void falar(String s) {
        if (!SILENCIOSO)
            System.out.println(s);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
            return;
        }
        int k, n;
        Partitioner part;
        try {
            k = Integer.parseInt(args[0]);
            n = Integer.parseInt(args[1]);
            part = new Partitioner(n);
            if (k < 0 || k >= n)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            System.out.println("Argumentos inválidos: k tem de estar em [0, N).");
            return;
        }
        String endereco = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";

        IpfsPubSub ps = new IpfsPubSub(api);
        RecordFile file = new RecordFile("lider-p" + k + ".txt");
        int porto = Wire.PORTO_BASE + k;

        // O porto é aberto ANTES de se começar a aceitar leituras: um líder que publica
        // um porto que
        // não serve (porque está ocupado) é pior do que um líder que não arranca.
        try (ServerSocket server = new ServerSocket(porto)) {
            seq = file.lastSeq(); // continua a numeração após reinício
            System.out.println("Líder p" + k + "/" + n + " pronto: recuperação em " + endereco + ":" + porto
                    + " | entrada em " + Wire.topicIn(k) + " | dados em " + Wire.topicData(k)
                    + " | seq atual = " + seq);

            Thread entrada = new Thread(() -> entrada(k, part, ps, file, endereco, porto), "entrada");
            entrada.start();

            Thread monitor = new Thread(() -> monitor(k), "monitor");
            monitor.setDaemon(true);
            monitor.start();

            while (true) {
                try (Socket s = server.accept(); // BLOQUEIA até uma réplica se ligar
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter out = new PrintWriter(
                                new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS);
                    String pedido = in.readLine(); // BLOQUEIA até chegar o pedido
                    atender(pedido, s, file, out);
                } catch (IOException e) {
                    // uma falha numa ligação de recuperação termina apenas ESSA ligação
                    System.out.println("Recuperação interrompida: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            // Ex.: BindException (porto de recuperação ocupado por outro processo)
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- recuperação

    static void atender(String pedido, Socket s, RecordFile file, PrintWriter out) throws IOException {
        String remoto = s.getInetAddress().getHostAddress() + ":" + s.getPort();
        if (pedido == null) {
            System.out.println("Recuperação: " + remoto + " fechou a ligação sem enviar o pedido");
            return;
        }
        String[] p = pedido.trim().split("\\s+");
        long de, ate;
        try {
            if (p.length != 3 || !p[0].equals("PEDIDO")) {
                throw new IllegalArgumentException("esperado: PEDIDO <de> <até>");
            }
            de = Long.parseLong(p[1]);
            ate = Long.parseLong(p[2]);
        } catch (IllegalArgumentException e) { // inclui NumberFormatException
            out.println("ERRO pedido mal formado");
            System.out.println("Recuperação: pedido mal formado de " + remoto + ": \"" + pedido + "\"");
            return;
        }
        if (de < 1 || de > ate) {
            out.println("ERRO intervalo inválido (de=" + de + ", até=" + ate + ")");
            System.out.println("Recuperação: intervalo inválido de " + remoto + ": " + de + ".." + ate);
            return;
        }
        long ultimo;
        synchronized (LOCK) {
            ultimo = seq; // o ficheiro tem, no mínimo, tudo até este seq
        }
        ate = Math.min(ate, ultimo); // nunca se devolve (nem se pede ao ficheiro) o que ainda não existe

        List<SensorRecord> regs = file.range(de, ate); // lê o ficheiro DO LÍDER: a fonte da verdade
        for (SensorRecord r : regs) {
            out.println(r.toLine());
        }
        out.println("FIM");
        if (out.checkError()) {
            throw new IOException("a réplica fechou a ligação a meio da resposta");
        }
        System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " para " + remoto + " (" + regs.size() + " registos)");
    }

    // ---------------------------------------------------------------- entrada

    static void entrada(int k, Partitioner part, IpfsPubSub ps, RecordFile file, String endereco, int porto) {
        try {
            ps.subscribe(Wire.topicIn(k), msg -> { // BLOQUEIA aqui: a resposta HTTP nunca termina
                try {
                    processarIn(msg, k, part, ps, file, endereco, porto);
                } catch (IOException e) {
                    // Ex.: daemon parado ao publicar. O registo JÁ está no ficheiro, mas não chegou
                    // às
                    // réplicas: serão elas a notar a lacuna e a recuperá-lo por TCP.
                    System.out.println("FALHA A PUBLICAR/ESCREVER: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            System.out.println("Subscrição de entrada terminada.");
        } catch (IOException | InterruptedException e) {
            System.out.println("ENTRADA FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // Um líder que já não recebe leituras mas continua a servir recuperações induz
        // em erro.
        System.exit(1);
    }

    static void processarIn(String msg, int k, Partitioner part, IpfsPubSub ps, RecordFile file,
            String endereco, int porto) throws IOException, InterruptedException {
        long agora = System.currentTimeMillis();
        String sensor;
        double temp;
        try {
            if (!Wire.type(msg).equals("IN"))
                throw new IllegalArgumentException("tipo");
            String[] f = Wire.body(msg).split(";");
            if (f.length != 2 || f[0].isBlank())
                throw new IllegalArgumentException("campos");
            sensor = f[0];
            temp = Double.parseDouble(f[1]);
        } catch (IllegalArgumentException e) { // inclui NumberFormatException
            synchronized (LOCK) {
                rejeitados++;
            }
            System.out.println("REJEITADO: " + msg);
            return;
        }

        int j = part.of(sensor);
        if (j != k) {
            // Só acontece com N diferente entre produtor e líder (ou um publicador a
            // ignorar a função
            // de partição). A verificação TEM de estar aqui: o líder não pode confiar no
            // produtor.
            synchronized (LOCK) {
                rejeitados++;
            }
            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + j);
            return;
        }

        SensorRecord r;
        synchronized (LOCK) {
            // seq + append na MESMA secção sincronizada. Se estivessem separados, o
            // atendimento TCP podia
            // ver um seq já atribuído cujo registo ainda não está no ficheiro (ou duas
            // leituras
            // trocarem a ordem entre o seq e o append). O seq só avança DEPOIS de o append
            // ter sucesso.
            long proximo = seq + 1;
            r = SensorRecord.now(proximo, sensor, temp);
            file.append(r); // 1) escreve localmente (fonte da recuperação)
            seq = proximo;
            if (contados == 0)
                primeiroMs = agora;
        }
        ps.publish(Wire.topicData(k), Wire.reg(endereco, porto, r)); // 2) propaga, SÓ depois de estar no ficheiro
        synchronized (LOCK) {
            contados++;
            ultimoMs = System.currentTimeMillis();
        }
        falar("Registado e publicado: " + r.toLine());
    }

    // 2 s sem leituras depois de ter registado algumas = fim da rajada -> resumo
    static void monitor(int k) {
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (LOCK) {
                if (contados > 0 && System.currentTimeMillis() - ultimoMs >= 2000) {
                    long dur = Math.max(1, ultimoMs - primeiroMs);
                    System.out.println("--- RESUMO líder p" + k + " --- registos=" + contados
                            + " | 1.º=" + primeiroMs + " ms | último=" + ultimoMs + " ms | duração=" + dur
                            + " ms | débito=" + (contados * 1000L / dur) + " registos/s | rejeitados=" + rejeitados
                            + " | último seq=" + seq);
                    contados = 0;
                    rejeitados = 0;
                }
            }
        }
    }
}