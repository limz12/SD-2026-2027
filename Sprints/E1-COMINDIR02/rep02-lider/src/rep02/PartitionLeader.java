package rep02;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Líder de uma partição: consome IN, persiste e publica REG, e serve recuperação TCP. */
public final class PartitionLeader {
    private final int partition;
    private final int partitionCount;
    private final String advertisedHost;
    private final int recoveryPort;
    private final IpfsPubSub pubSub;
    private final RecordFile file;
    private final Partitioner partitioner;

    // Atribuição de seq/append e leitura de range/lastSeq partilham este mesmo lock.
    private final Object fileStateLock = new Object();
    private long lastSequence;

    private PartitionLeader(int partition, int partitionCount, String host, String api)
            throws IOException {
        this.partition = partition;
        this.partitionCount = partitionCount;
        this.advertisedHost = host;
        this.recoveryPort = Wire.PORTO_BASE + partition;
        this.pubSub = new IpfsPubSub(api);
        this.file = new RecordFile("lider-p" + partition + ".txt");
        this.partitioner = new Partitioner(partitionCount);
        this.lastSequence = file.lastSeq();
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2 || args.length > 4) {
                throw new IllegalArgumentException(
                        "Uso: PartitionLeader <k> <N> [endereço-anunciado] [api]");
            }
            int partition = Integer.parseInt(args[0]);
            int count = Integer.parseInt(args[1]);
            if (count < 1 || partition < 0 || partition >= count) {
                throw new IllegalArgumentException("Exige-se N >= 1 e 0 <= k < N");
            }
            String host = args.length >= 3 ? args[2] : "127.0.0.1";
            String api = args.length >= 4 ? args[3] : "http://127.0.0.1:5001/api/v0";
            if (host.isBlank() || host.indexOf(';') >= 0) {
                throw new IllegalArgumentException("Endereço anunciado inválido");
            }
            int recoveryPort = Wire.PORTO_BASE + partition;
            if (recoveryPort > 65535) {
                throw new IllegalArgumentException("Porto de recuperação fora do intervalo TCP");
            }
            new PartitionLeader(partition, count, host, api).start();
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Não foi possível iniciar o líder: " + e.getMessage());
        }
    }

    private void start() throws IOException {
        // Abre primeiro a porta para não consumir entradas se o serviço TCP não puder iniciar.
        try (ServerSocket server = new ServerSocket(recoveryPort)) {
            System.out.println("Líder p" + partition + " pronto: tópico " + Wire.topicIn(partition)
                    + " → " + Wire.topicData(partition) + ", TCP " + recoveryPort
                    + ", endereço anunciado=" + advertisedHost + ", N=" + partitionCount
                    + ", seq inicial=" + lastSequence);

            Thread inputThread = new Thread(this::subscribeToInput, "lider-entrada-p" + partition);
            inputThread.start();

            while (true) {
                try (Socket socket = server.accept()) {
                    serveRecovery(socket);
                } catch (IOException e) {
                    System.err.println("Recuperação interrompida: " + e.getMessage());
                }
            }
        }
    }

    /** Thread bloqueante de entrada PubSub; erros de uma mensagem não param o líder. */
    private void subscribeToInput() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pubSub.subscribe(Wire.topicIn(partition), message -> {
                    try {
                        processInput(message);
                    } catch (IOException e) {
                        System.err.println("ERRO ao persistir entrada: " + e.getMessage());
                    }
                });
                System.err.println("Subscrição de entrada terminou; nova tentativa em 1 s.");
            } catch (IOException e) {
                System.err.println("Subscrição de entrada falhou: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("Erro na subscrição de entrada: " + e.getMessage());
            }
            pauseBeforeRetry();
        }
    }

    private void processInput(String message) throws IOException {
        SensorRecord record;
        try {
            if (!"IN".equals(Wire.type(message))) {
                throw new IllegalArgumentException("Tipo de mensagem inesperado");
            }
            String[] fields = Wire.body(message).split(";", -1);
            if (fields.length != 2 || fields[0].isBlank()) {
                throw new IllegalArgumentException("Formato esperado: IN;<sensor>;<temperatura>");
            }
            double temperature = Double.parseDouble(fields[1]);
            if (!Double.isFinite(temperature)) {
                throw new IllegalArgumentException("Temperatura não finita");
            }
            String sensor = fields[0];
            int actualPartition = partitioner.of(sensor);
            if (actualPartition != partition) {
                System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + actualPartition);
                return;
            }

            synchronized (fileStateLock) {
                if (lastSequence == Long.MAX_VALUE) {
                    throw new IOException("Número de sequência esgotado");
                }
                record = SensorRecord.now(lastSequence + 1, sensor, temperature);
                // O incremento só é confirmado depois do append durável no ficheiro local.
                file.append(record);
                lastSequence = record.getSeq();
            }
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + message + " (" + e.getMessage() + ")");
            return;
        }

        try {
            pubSub.publish(Wire.topicData(partition), Wire.reg(advertisedHost, recoveryPort, record));
            System.out.println("PUBLICADO p" + partition + ": " + record.toLine());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("PUBLICAÇÃO INTERROMPIDA para seq=" + record.getSeq());
        } catch (IOException e) {
            // O registo fica no líder mesmo se a publicação falhar; esta ficha não implementa replay.
            System.err.println("PUBLICAÇÃO FALHOU seq=" + record.getSeq() + ": " + e.getMessage());
        }
    }

    /** Atende uma ligação, valida PEDIDO e responde com linhas e FIM/ERRO. */
    private void serveRecovery(Socket socket) throws IOException {
        socket.setSoTimeout(Wire.T_TCP_MS);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String request = input.readLine();
            long from;
            long requestedTo;
            try {
                if (request == null) {
                    throw new IllegalArgumentException("pedido vazio");
                }
                String[] fields = request.trim().split("\\s+");
                if (fields.length != 3 || !"PEDIDO".equals(fields[0])) {
                    throw new IllegalArgumentException("formato esperado: PEDIDO <de> <até>");
                }
                from = Long.parseLong(fields[1]);
                requestedTo = Long.parseLong(fields[2]);
                if (from < 1 || from > requestedTo) {
                    throw new IllegalArgumentException("intervalo inválido");
                }
            } catch (IllegalArgumentException e) {
                output.println("ERRO " + e.getMessage());
                return;
            }

            long actualTo;
            List<SensorRecord> records;
            synchronized (fileStateLock) {
                actualTo = Math.min(requestedTo, lastSequence);
                records = file.range(from, actualTo);
            }
            for (SensorRecord record : records) {
                output.println(record.toLine());
            }
            output.println("FIM");
            System.out.println("RECUPERAÇÃO: " + from + ".." + actualTo + " para "
                    + socket.getRemoteSocketAddress() + " (" + records.size() + " registos)");
        }
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
