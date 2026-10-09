import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public class PartitionLeader {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
            return;
        }

        int k = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]); // Parte A: 0 1
        String endereco = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";

        IpfsPubSub ps = new IpfsPubSub(api);
        RecordFile file = new RecordFile("lider-p" + k + ".txt");
        int porto = Wire.PORTO_BASE + k;

        final long[] ultimo = { file.lastSeq() };  // número da última sequência partilhada, visível nas 2 threads

        Thread entrada = new Thread(() -> {
            ps.subscribe(Wire.topicIn(k)).forEach(jsonLine -> {
                try {
                    String msg = IpfsPubSub.topicData(jsonLine);
                    if (msg == null || msg.isEmpty()) {
                        return;
                    }

                    // Verifica se o tipo da mensagem é "IN"
                    if (!"IN".equals(Wire.type(msg))) {
                        return;
                    }

                    String body = Wire.body(msg);
                    String[] parts = body.split(";", 2);
                    if (parts.length < 2) {
                        throw new IllegalArgumentException("Corpo IN mal formado: " + body);
                    }

                    String sensor = parts[0];
                    double temperatura = Double.parseDouble(parts[1]);

                    String fullInstante = Instant.now().toString();
                    String instante = fullInstante.length() > 19 ? fullInstante.substring(0, 19) : fullInstante;

                    SensorRecord r;
                    synchronized (file) {
                        long seq = ++ultimo[0];
                        r = new SensorRecord(seq, sensor, temperatura, instante);
                        file.append(r);
                    }

                    // Publica no tópico de dados correto (topicData) e não no de entrada
                    ps.publish(Wire.topicData(k), Wire.reg(endereco, porto, r));

                } catch (Exception e) {
                    System.out.println("REJEITADO: " + jsonLine + " (" + e.getMessage() + ")");
                }
            });
        });
        entrada.start();

        try (ServerSocket server = new ServerSocket(porto)) {
            while (true) {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(
                             new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS);
                    String pedido = in.readLine();

                    if (pedido == null || pedido.trim().isEmpty()) {
                        out.println("ERRO Pedido vazio");
                        continue;
                    }

                    String[] p = pedido.trim().split("\\s+");
                    if (p.length < 3 || !p[0].equals("PEDIDO")) {
                        out.println("ERRO Formato de pedido inválido. Use: PEDIDO <de> <até>");
                        continue;
                    }

                    long de, ate;
                    try {
                        de = Long.parseLong(p[1]);
                        ate = Long.parseLong(p[2]);
                    } catch (NumberFormatException e) {
                        out.println("ERRO Valores de sequência não numéricos");
                        continue;
                    }

                    // Validação: de > ate deve retornar ERRO
                    if (de > ate) {
                        out.println("ERRO Sequencia inicial maior que a final");
                        continue;
                    }

                    // Limita <até> ao último seq gravado
                    long maxSeq;
                    synchronized (file) {
                        maxSeq = file.lastSeq();
                    }
                    if (ate > maxSeq) {
                        ate = maxSeq;
                    }

                    List<SensorRecord> rangeList = file.range(de, ate);
                    for (SensorRecord r : rangeList) {
                        out.println(r.toLine());
                    }
                    out.println("FIM");

                    String remoto = s.getRemoteSocketAddress().toString();
                    System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " para " + remoto + " (" + rangeList.size() + " registos)");

                } catch (IOException e) {
                    System.out.println("Recuperação interrompida: " + e.getMessage());
                }
            }
        }
    }
}