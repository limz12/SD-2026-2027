package rep04;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Líder da partição k. Duas atividades bloqueantes em simultâneo:
 *
 * ENTRADA (thread "entrada") subscreve rep04-in-p<k> e fica bloqueada em
 * subscribe(), à espera
 * de cada IN. Atribui seq, escreve no ficheiro e publica REG.
 * RECUPERAÇÃO (thread principal) fica bloqueada em accept() e depois em
 * readLine(). Atende as
 * réplicas UMA DE CADA VEZ, lendo o ficheiro com range().
 *
 * Estado partilhado: o último seq atribuído ("seq"), o relógio de Lamport
 * ("lamport") e o relógio
 * vetorial ("vc"), todos protegidos por LOCK, e o ficheiro (cujos métodos são
 * synchronized).
 * A entrada ESCREVE-os; a recuperação só lê o seq, para limitar o "até" do
 * pedido.
 *
 * Heartbeat e lote (COMINDIR04): o líder já não publica um REG por registo.
 * Cada registo, depois de ter
 * seq, L, V e estar no ficheiro, fica na lista "pendentes" (dentro da MESMA
 * secção sincronizada). A cada
 * Δ = PERIODO_HB_MS, enviarLote() troca "pendentes" por uma lista nova, lê o
 * ultimoSeq e incrementa o hb
 * (tudo sob LOCK) e publica o LOTE FORA do LOCK. O LOTE é emitido mesmo vazio:
 * é o heartbeat.
 * Os heartbeats NÃO são eventos: não alteram L nem V, e V[k] == seq mantém-se.
 *
 * Relógios (os EVENTOS são as atribuições de seq feitas por este líder):
 * Lamport: L = max(L, L_msg) + 1
 * Vetorial: V[i] = max(V[i], V_msg[i]) para todo o i; depois V[k] = V[k] + 1
 * (V[k] == seq)
 * Tudo isto, mais a atribuição do seq e o append, numa ÚNICA secção
 * sincronizada.
 *
 * Uso: java -cp out rep04.PartitionLeader <k> <N> [endereço=127.0.0.1] [api]
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
    static long lamport; // relógio de Lamport deste líder (sob LOCK)
    static VectorClock vc; // relógio vetorial deste líder, dimensão N (sob LOCK)
    static long inc; // incarnação: instante de arranque, em ms
    static long hb; // nº do último heartbeat (lote) fechado nesta incarnação (sob LOCK)
    static List<SensorRecord> pendentes = new ArrayList<>(); // registos com seq mas ainda não publicados (sob LOCK)

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
        RecordFile file = new RecordFile("lider-p" + k + ".txt", n);
        int porto = Wire.PORTO_BASE + k;

        // O porto é aberto ANTES de se começar a aceitar leituras: um líder que publica
        // um porto que
        // não serve (porque está ocupado) é pior do que um líder que não arranca.
        try (ServerSocket server = new ServerSocket(porto)) {
            // Reinício: seq, L e V continuam do ÚLTIMO registo escrito. Partir de zero
            // faria o líder reutilizar
            // números e dar a eventos novos carimbos MENORES que os de eventos já
            // publicados (a condição de
            // relógio deixava de se verificar), e V[k] deixava de coincidir com o seq.
            SensorRecord ultimo = file.last();
            if (ultimo == null) {
                seq = 0;
                lamport = 0;
                vc = new VectorClock(n);
            } else {
                seq = ultimo.getSeq();
                lamport = ultimo.getLamport();
                vc = VectorClock.parse(ultimo.getVector().toString(), n); // IllegalArgumentException se a dimensão != N
                if (vc.get(k) != seq) {
                    System.out.println("Ficheiro inconsistente: última linha com V[" + k + "]=" + vc.get(k)
                            + " != seq=" + seq);
                    System.exit(1);
                }
            }
            System.out.println("Líder p" + k + "/" + n + " pronto: recuperação em " + endereco + ":" + porto
                    + " | entrada em " + Wire.topicIn(k) + " | dados em " + Wire.topicData(k));
            System.out.println("Estado inicial: seq=" + seq + " L=" + lamport + " V=" + vc);

            inc = System.currentTimeMillis();
            hb = 0;
            System.out.println("Incarnação inc=" + inc + " | período do heartbeat = " + Wire.PERIODO_HB_MS + " ms");

            Thread entrada = new Thread(() -> entrada(k, part, ps, file, endereco, porto), "entrada");
            entrada.start();

            // Heartbeat/lote: um heartbeat no arranque e depois um a cada Δ.
            // scheduleAtFixedRate: se o processo
            // ficar suspenso, as execuções em atraso são feitas de seguida (rajada de lotes
            // após a retoma).
            ScheduledExecutorService agendador = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "heartbeat");
                t.setDaemon(true);
                return t;
            });
            agendador.scheduleAtFixedRate(() -> enviarLote(k, ps, endereco, porto), 0, Wire.PERIODO_HB_MS,
                    TimeUnit.MILLISECONDS);

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
        } catch (IOException | IllegalArgumentException e) {
            // Ex.: BindException (porto de recuperação ocupado por outro processo),
            // ficheiro com N diferente
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
                    processarIn(msg, k, part, file);
                } catch (IOException e) {
                    // falha ao escrever no ficheiro: a leitura NÃO foi registada (o seq não
                    // avançou)
                    System.out.println("FALHA A ESCREVER: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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

    static void processarIn(String msg, int k, Partitioner part, RecordFile file) throws IOException {
        long agora = System.currentTimeMillis();
        String sensor;
        double temp;
        long lmsg;
        VectorClock vmsg;
        try {
            if (!Wire.type(msg).equals("IN"))
                throw new IllegalArgumentException("tipo");
            String[] f = Wire.body(msg).split(";");
            if (f.length != 4 || f[0].isBlank())
                throw new IllegalArgumentException("campos");
            sensor = f[0];
            temp = Double.parseDouble(f[1]);
            lmsg = Long.parseLong(f[2]);
            vmsg = VectorClock.parse(f[3], part.count()); // dimensão != N (produtor com outro N) -> rejeitado
        } catch (IllegalArgumentException e) { // inclui NumberFormatException
            synchronized (LOCK) {
                rejeitados++;
            }
            System.out.println("REJEITADO: " + msg + " (" + e.getMessage() + ")");
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
            // UMA só secção sincronizada: relógios + seq + verificação + append. Se
            // estivessem separados,
            // dois eventos podiam intercalar-se e ficar com (L, V, seq) incoerentes entre
            // si (por exemplo, o
            // V de um evento com a componente k de outro), e o atendimento TCP podia ver um
            // seq atribuído
            // cujo registo ainda não está no ficheiro. Nada é "confirmado" (seq, lamport,
            // vc) antes de o
            // append ter sucesso.
            long proximo = seq + 1;
            long novoL = Math.max(lamport, lmsg) + 1; // Lamport: max(L, L_msg) + 1
            VectorClock novoV = vc.copy();
            novoV.merge(vmsg); // vetorial: máximo componente a componente
            novoV.tick(k); // ... e depois V[k] = V[k] + 1
            if (novoV.get(k) != proximo) {
                // invariante V[k] == seq violada (ex.: estado de relógio reposto a zero após um
                // reinício)
                rejeitados++;
                System.out.println("ERRO DE INVARIANTE: V[" + k + "]=" + novoV.get(k) + " != seq=" + proximo
                        + " — leitura não registada");
                return;
            }
            r = SensorRecord.now(proximo, sensor, temp, novoL, novoV.copy()); // copy(): o registo guarda um instantâneo
            file.append(r); // 1) escreve localmente (fonte da recuperação)
            seq = proximo;
            lamport = novoL;
            vc = novoV;
            pendentes.add(r); // fica à espera do próximo lote, SÓ depois de estar no ficheiro
            if (contados == 0)
                primeiroMs = agora;
            contados++;
            ultimoMs = System.currentTimeMillis();
        }
        falar("EVENTO: seq=" + r.getSeq() + " L=" + r.getLamport() + " V=" + r.getVector() + " (pendente)");
    }

    // Fecha e publica um lote (que é também o heartbeat). Executada pela thread
    // "heartbeat".
    static void enviarLote(int k, IpfsPubSub ps, String endereco, int porto) {
        try {
            List<SensorRecord> lote;
            long ultimoSeq, hbAtual;
            synchronized (LOCK) {
                // Na MESMA secção sincronizada em que se atribui o seq: troca a lista, lê o
                // ultimoSeq e
                // numera o heartbeat. Se o ultimoSeq fosse lido fora, um registo atribuído
                // entre a troca e a
                // leitura faria o cabeçalho prometer um seq que NÃO está neste lote nem nos
                // anteriores (as
                // réplicas pediriam por TCP um registo que ainda vai chegar no lote seguinte).
                lote = pendentes;
                pendentes = new ArrayList<>();
                ultimoSeq = seq;
                hbAtual = ++hb;
                // L e V NÃO são tocados: um heartbeat não é um evento.
            }
            // FORA da secção sincronizada: a publicação é um pedido HTTP lento; dentro do
            // LOCK bloquearia
            // a atribuição de seq (e, com ela, a entrada de leituras) durante todo o
            // pedido.
            Batch b = new Batch(endereco, porto, inc, hbAtual, ultimoSeq, lote);
            String msg = b.encode();
            ps.publish(Wire.topicData(k), msg);
            long agora = System.currentTimeMillis();
            int bytes = msg.getBytes(StandardCharsets.UTF_8).length;
            Files.writeString(Paths.get("hb-p" + k + ".txt"),
                    inc + ";" + hbAtual + ";" + agora + ";" + ultimoSeq + ";" + lote.size() + ";" + bytes + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("HEARTBEAT: hb=" + hbAtual + " n=" + lote.size() + " ultimoSeq=" + ultimoSeq);
        } catch (Throwable e) {
            // Uma exceção que escapasse cancelaria TODAS as execuções seguintes do
            // agendador. Se a publicação
            // falhar, os registos do lote NÃO são republicados: estão no ficheiro, e os
            // participantes
            // recuperam-nos por TCP através do ultimoSeq do lote seguinte.
            System.out.println("FALHA A PUBLICAR LOTE: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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