
package rep04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PartitionLeader {

    static final Object LOCK = new Object();

    // Estado dos registos e relógios
    static long ultimoSeq;
    static long lamport;
    static VectorClock vector;

    static RecordFile file;

    // Identificação da partição
    static int k;
    static int N;
    static Partitioner partitioner;

    // Informação anunciada às réplicas
    static String hostAnunciado;
    static int porto;
    static IpfsPubSub ps;

    // Estado dos heartbeats
    static long inc;
    static long hb;

    // Registos a incluir no próximo lote
    static List<SensorRecord> pendentes = new ArrayList<>();

    // Ficheiro de métricas dos heartbeats
    static Path ficheiroHB;

    /**
     * Processa uma mensagem IN recebida do produtor.
     *
     * Formato:
     * IN;sensor;temperatura;L;V
     */
    static void onIn(String msg) {

        String tipo;

        try {
            tipo = Wire.type(msg);

        } catch (IllegalArgumentException e) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        if (!tipo.equals("IN")) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        String corpo = Wire.body(msg);
        String[] f = corpo.split(";", -1);

        // Exigir quatro campos no corpo
        if (f.length != 4) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        String sensor = f[0].trim();

        double temp;
        long lamportRecebido;
        VectorClock vectorRecebido;

        try {

            temp = Double.parseDouble(f[1].trim());

            lamportRecebido = Long.parseLong(
                f[2].trim()
            );

            vectorRecebido = VectorClock.parse(
                f[3].trim(), N
            );

        } catch (IllegalArgumentException e) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        if (sensor.isEmpty()
                || !Double.isFinite(temp)
                || lamportRecebido < 0) {

            System.out.println(
                "REJEITADO: " + msg.trim()
            );
            return;
        }

        // Verificar se o sensor pertence à partição
        int destino = partitioner.of(sensor);

        if (destino != k) {

            System.out.println(
                "PARTIÇÃO ERRADA: " + sensor
                + " é de p" + destino
                + ", aqui é p" + k
            );
            return;
        }

        // Atualização sincronizada dos relógios,
        // sequência, persistência e lista de pendentes
        synchronized (LOCK) {

            try {

                // 1. Atualizar Lamport
                long novoLamport = Math.addExact(
                    Math.max(lamport, lamportRecebido),
                    1
                );

                // 2. Atualizar o relógio vetorial
                VectorClock novoVector = vector.copy();

                novoVector.merge(vectorRecebido);
                novoVector.tick(k);

                // 3. Atribuir a próxima sequência
                long novoSeq = Math.addExact(
                    ultimoSeq, 1
                );

                // 4. Verificar V[k] == seq
                if (novoVector.get(k) != novoSeq) {

                    System.out.println(
                        "ERRO: V[" + k + "] = "
                        + novoVector.get(k)
                        + " diferente de seq = " + novoSeq
                    );
                    return;
                }

                // 5. Criar o registo
                SensorRecord r = SensorRecord.now(
                    novoSeq,
                    sensor,
                    temp,
                    novoLamport,
                    novoVector
                );

                // 6. Persistir o registo
                file.append(r);

                // 7. Confirmar o novo estado
                ultimoSeq = novoSeq;
                lamport = novoLamport;
                vector = novoVector;

                // 8. Adicionar ao próximo lote
                pendentes.add(r);

                System.out.println(
                    "EVENTO: seq=" + ultimoSeq
                    + " L=" + lamport
                    + " V=" + vector
                );

            } catch (IOException e) {

                System.out.println(
                    "Falha a persistir: "
                    + e.getMessage()
                );

            } catch (ArithmeticException e) {

                System.out.println(
                    "Erro ao atualizar relógios: "
                    + e.getMessage()
                );
            }
        }
    }

    
    /**
     * Envia um lote de registos e um heartbeat.
     *
     * A lista de pendentes é trocada dentro do LOCK.
     * A publicação e o registo das métricas são feitos
     * fora da secção sincronizada.
     *
     * O ficheiro hb-p<k>.txt só é atualizado
     * se a publicação terminar com sucesso.
     */
    static void enviarLote() {

        List<SensorRecord> loteRegistos;
        long seqLote;
        long hbLote;

        // SECÇÃO SINCRONIZADA
        synchronized (LOCK) {

            // Guardar os registos pendentes
            loteRegistos = pendentes;

            // Preparar a lista para os próximos registos
            pendentes = new ArrayList<>();

            // Obter a última sequência persistida
            seqLote = ultimoSeq;

            // Incrementar o heartbeat
            hb++;
            hbLote = hb;
        }

        // Construir o lote fora do bloqueio
        Batch lote = new Batch(
            hostAnunciado,
            porto,
            inc,
            hbLote,
            seqLote,
            loteRegistos
        );

        String mensagem = lote.encode();

        int n = loteRegistos.size();

        int bytes = mensagem.getBytes(
            StandardCharsets.UTF_8
        ).length;

        // Instante da tentativa de publicação
        long instante = System.currentTimeMillis();

        try {

            // Publicar o lote
            ps.publish(
                Wire.topicData(k),
                mensagem
            );

            // A publicação terminou com sucesso.
            // Só agora acrescentar a linha ao ficheiro.
            String linhaHB =
                    inc + ";"
                    + hbLote + ";"
                    + instante + ";"
                    + seqLote + ";"
                    + n + ";"
                    + bytes
                    + System.lineSeparator();

            try {

                Files.writeString(
                    ficheiroHB,
                    linhaHB,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );

            } catch (IOException e) {

                System.out.println(
                    "Falha a registar heartbeat: "
                    + e.getMessage()
                );
            }

            System.out.println(
                "HEARTBEAT: hb=" + hbLote
                + " n=" + n
                + " ultimoSeq=" + seqLote
            );

        } catch (IOException e) {

            // Publicação falhou: não escrever no ficheiro
            System.out.println(
                "Falha a publicar heartbeat "
                + hbLote + ": " + e.getMessage()
            );

        } catch (InterruptedException e) {

            // Publicação interrompida: não escrever no ficheiro
            System.out.println(
                "Publicação do heartbeat "
                + hbLote + " interrompida: "
                + e.getMessage()
            );

            Thread.currentThread().interrupt();
        }

        // Os registos retirados de pendentes não são
        // reenviados, mesmo que a publicação falhe.
        // A recuperação fica a cargo das réplicas,
        // através do ultimoSeq de lotes posteriores.
}


    /**
     * Atende um pedido de recuperação TCP.
     *
     * Formato:
     * PEDIDO <de> <ate>
     */
    static void atender(Socket s) {

        String remoto =
            s.getRemoteSocketAddress().toString();

        try (
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    s.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );

            PrintWriter out = new PrintWriter(
                s.getOutputStream(),
                true,
                StandardCharsets.UTF_8
            )
        ) {

            // Timeout de leitura
            s.setSoTimeout(Wire.T_TCP_MS);

            String pedido = in.readLine();

            if (pedido == null) {
                return;
            }

            String[] p = pedido.trim().split("\\s+");

            long de;
            long ate;

            if (p.length != 3
                    || !p[0].equals("PEDIDO")) {

                out.println("ERRO pedido mal formado");
                return;
            }

            try {

                de = Long.parseLong(p[1]);
                ate = Long.parseLong(p[2]);

            } catch (NumberFormatException e) {

                out.println("ERRO pedido mal formado");
                return;
            }

            if (de <= 0 || ate <= 0 || de > ate) {

                out.println("ERRO intervalo inválido");
                return;
            }

            List<SensorRecord> recs;
            long topo;

            // Consultar a sequência e obter os registos
            // dentro da secção sincronizada
            synchronized (LOCK) {

                topo = Math.min(ate, ultimoSeq);

                try {

                    recs = (de > topo)
                            ? List.of()
                            : file.range(de, topo);

                } catch (IOException e) {

                    out.println(
                        "ERRO leitura do ficheiro"
                    );
                    return;
                }
            }

            // Enviar registos fora do bloqueio
            for (SensorRecord r : recs) {

                out.println(r.toLine());
            }

            out.println("FIM");

            System.out.println(
                "RECUPERAÇÃO: " + de + ".." + topo
                + " para " + remoto
                + " (" + recs.size() + " registos)"
            );

        } catch (IOException e) {

            System.out.println(
                "Falha no atendimento a " + remoto
                + ": " + e.getMessage()
            );

        } finally {

            try {

                s.close();

            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Arranque do líder da partição.
     *
     * Argumentos:
     * PartitionLeader <k> <N> [endereco] [api]
     */
    public static void main(String[] args)
            throws Exception {

        if (args.length < 2 || args.length > 4) {

            System.out.println(
                "Uso: PartitionLeader <k> <N> "
                + "[endereco=127.0.0.1] [api]"
            );
            return;
        }

        try {

            k = Integer.parseInt(args[0]);
            N = Integer.parseInt(args[1]);

        } catch (NumberFormatException e) {

            System.out.println(
                "Erro: k e N devem ser inteiros."
            );
            return;
        }

        if (N <= 0 || k < 0 || k >= N) {

            System.out.println(
                "k tem de satisfazer 0 <= k < N, com N > 0"
            );
            return;
        }

        hostAnunciado = args.length > 2
                ? args[2]
                : "127.0.0.1";

        String api = args.length > 3
                ? args[3]
                : "http://127.0.0.1:5001/api/v0";

        porto = Wire.PORTO_BASE + k;

        partitioner = new Partitioner(N);

        file = new RecordFile(
            "lider-p" + k + ".txt", N
        );

        // Ficheiro de métricas dos heartbeats
        ficheiroHB = Paths.get(
            "hb-p" + k + ".txt"
        );

        // Inicializar encarnação e heartbeat
        inc = System.currentTimeMillis();
        hb = 0;

        // Inicializar lista de pendentes
        pendentes = new ArrayList<>();

        // Recuperar sequência e relógios
        // a partir do último registo persistido
        synchronized (LOCK) {

            SensorRecord ultimo = file.last();

            if (ultimo == null) {

                ultimoSeq = 0;
                lamport = 0;
                vector = new VectorClock(N);

            } else {

                ultimoSeq = ultimo.getSeq();
                lamport = ultimo.getLamport();
                vector = ultimo.getVector();

                if (vector.get(k) != ultimoSeq) {

                    throw new IllegalStateException(
                        "Estado inicial inconsistente: "
                        + "V[" + k + "] = "
                        + vector.get(k)
                        + ", seq = " + ultimoSeq
                    );
                }
            }
        }

        ps = new IpfsPubSub(api);

        // Thread de receção das mensagens IN
        Thread entrada = new Thread(() -> {

            try {

                ps.subscribe(
                    Wire.topicIn(k),
                    PartitionLeader::onIn
                );

            } catch (IOException | InterruptedException e) {

                System.out.println(
                    "Subscrição terminada: "
                    + e.getMessage()
                );

                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

        }, "entrada-p" + k);

        // Serviço periódico de heartbeats
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();

        // Criar primeiro o servidor TCP.
        // Só iniciar as atividades se o porto estiver livre.
        try (ServerSocket ss = new ServerSocket(porto)) {

            System.out.println(
                "Líder p" + k
                + ": seq inicial = " + ultimoSeq
                + ", L = " + lamport
                + ", V = " + vector
            );

            System.out.println(
                "Incarnation: " + inc
            );

            System.out.println(
                "Heartbeat inicial: " + hb
            );

            System.out.println(
                "Período de heartbeat: "
                + Wire.PERIODO_HB_MS + " ms"
            );

            System.out.println(
                "Tópico de entrada: "
                + Wire.topicIn(k)
            );

            System.out.println(
                "Tópico de dados: "
                + Wire.topicData(k)
            );

            System.out.println(
                "Servidor de recuperação à escuta em "
                + hostAnunciado + ":" + porto
            );

            // Iniciar subscrição do produtor
            entrada.start();

            // Primeiro heartbeat imediato.
            // Depois, um heartbeat a cada período.
            scheduler.scheduleAtFixedRate(
                PartitionLeader::enviarLote,
                0,
                Wire.PERIODO_HB_MS,
                TimeUnit.MILLISECONDS
            );

            // Atendimento sequencial das recuperações
            while (true) {

                Socket s = ss.accept();
                atender(s);
            }

        } finally {

            // Parar o scheduler ao terminar o líder
            scheduler.shutdownNow();
        }
    }
}
