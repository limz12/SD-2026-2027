package rep02;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Réplica com uma subscrição, estado e ficheiro independentes por partição atribuída. */
public final class PartitionReplica {
    private final String replicaId;
    private final int partitionCount;
    private final String api;
    private final double lossProbability;
    private final long discardSequence;
    private final List<PartitionState> states = new ArrayList<>();

    private PartitionReplica(String replicaId, int partitionCount, List<Integer> partitions,
                             String api, double lossProbability, long discardSequence)
            throws IOException {
        this.replicaId = replicaId;
        this.partitionCount = partitionCount;
        this.api = api;
        this.lossProbability = lossProbability;
        this.discardSequence = discardSequence;
        for (int partition : partitions) {
            RecordFile file = new RecordFile("replica-" + replicaId + "-p" + partition + ".txt");
            states.add(new PartitionState(partition, file, file.lastSeq()));
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2 || args.length > 3) {
                throw new IllegalArgumentException(
                        "Uso: PartitionReplica <id> <partições-separadas-por-vírgulas> [portoApi]");
            }
            String id = args[0];
            if (id.isBlank() || id.contains("/") || id.contains("\\") || id.contains(";")) {
                throw new IllegalArgumentException("Identificador da réplica inválido");
            }
            String[] requestedPartitions = args[1].split(",", -1);
            LinkedHashSet<Integer> uniquePartitions = new LinkedHashSet<>();
            for (String value : requestedPartitions) {
                int partition = Integer.parseInt(value.trim());
                if (partition < 0 || !uniquePartitions.add(partition)) {
                    throw new IllegalArgumentException("Lista de partições inválida: " + args[1]);
                }
            }
            int n = 3;
            for (int partition : uniquePartitions) {
                if (partition >= n) {
                    throw new IllegalArgumentException("Com N=3, as partições válidas são 0, 1 e 2");
                }
            }
            String api = apiFromArgument(args.length == 3 ? args[2] : "5001");
            double probability = Double.parseDouble(System.getProperty("perda", "0"));
            if (probability < 0.0 || probability > 1.0 || !Double.isFinite(probability)) {
                throw new IllegalArgumentException("-Dperda tem de estar entre 0 e 1");
            }
            long discard = Long.parseLong(System.getProperty("descartar", "-1"));
            if (discard == 0 || discard < -1) {
                throw new IllegalArgumentException("-Ddescartar tem de ser um seq positivo");
            }

            PartitionReplica replica = new PartitionReplica(id, n,
                    new ArrayList<>(uniquePartitions), api, probability, discard);
            replica.start();
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Não foi possível iniciar a réplica: " + e.getMessage());
        }
    }

    private static String apiFromArgument(String argument) {
        if (argument.startsWith("http://") || argument.startsWith("https://")) {
            return argument;
        }
        int apiPort = Integer.parseInt(argument);
        if (apiPort < 1 || apiPort > 65535) {
            throw new IllegalArgumentException("Porto da API inválido: " + argument);
        }
        return "http://127.0.0.1:" + apiPort + "/api/v0";
    }

    private void start() {
        System.out.println("Réplica " + replicaId + " | N=" + partitionCount + " | API=" + api
                + " | perda=" + lossProbability + " | descartar=" + discardSequence);
        for (PartitionState state : states) {
            System.out.println("  p" + state.partition + ": " + Wire.topicData(state.partition)
                    + " → " + state.fileName() + " (seq inicial=" + state.lastSequence + ")");
            Thread subscription = new Thread(() -> subscribe(state),
                    "replica-" + replicaId + "-sub-p" + state.partition);
            subscription.start();
        }
    }

    /** Cada partição tem uma thread bloqueante própria; uma recuperação não bloqueia as outras. */
    private void subscribe(PartitionState state) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                new IpfsPubSub(api).subscribe(Wire.topicData(state.partition), message -> {
                    try {
                        state.processPubSub(message);
                    } catch (IOException e) {
                        System.err.println("ERRO na réplica " + replicaId + " p" + state.partition
                                + ": " + e.getMessage());
                    }
                });
                System.err.println("Subscrição p" + state.partition
                        + " terminou; nova tentativa em 1 s.");
            } catch (IOException e) {
                System.err.println("Subscrição p" + state.partition + " falhou: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("Erro na subscrição p" + state.partition + ": " + e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Estado inteiramente privado de uma partição e acedido pela sua thread de subscrição. */
    private final class PartitionState {
        private final int partition;
        private final RecordFile file;
        private final TreeMap<Long, SensorRecord> retained = new TreeMap<>();
        private final Random random = new Random();
        // As sequências são independentes por partição; cada partição pode
        // descartar uma vez a sua primeira receção do seq configurado.
        private final AtomicBoolean discardUsed = new AtomicBoolean();
        private long lastSequence;
        private String leaderHost;
        private int leaderPort;

        private PartitionState(int partition, RecordFile file, long lastSequence) {
            this.partition = partition;
            this.file = file;
            this.lastSequence = lastSequence;
        }

        private String fileName() {
            return "replica-" + replicaId + "-p" + partition + ".txt";
        }

        /** Processa só mensagens PubSub; as mensagens recebidas por TCP não passam pelos filtros. */
        private synchronized void processPubSub(String message) throws IOException {
            SensorRecord record;
            try {
                String[] parts = Wire.regParts(message);
                if (parts[0].isBlank()) {
                    throw new IllegalArgumentException("endereço do líder vazio");
                }
                int announcedPort = Integer.parseInt(parts[1]);
                if (announcedPort < 1 || announcedPort > 65535) {
                    throw new IllegalArgumentException("porto do líder inválido");
                }
                // Cada REG atualiza o destino de recuperação, inclusive quando a mensagem é descartada.
                leaderHost = parts[0];
                leaderPort = announcedPort;
                record = SensorRecord.fromLine(parts[2]);
            } catch (IllegalArgumentException e) {
                System.out.println("REJEITADO: " + message + " (" + e.getMessage() + ")");
                return;
            }

            if (record.getSeq() == discardSequence && discardUsed.compareAndSet(false, true)) {
                System.out.println("DESCARTADO (simulação): " + record.getSeq());
                checkRecoveryThreshold();
                return;
            }
            if (random.nextDouble() < lossProbability) {
                System.out.println("DESCARTADO (simulação): " + record.getSeq());
                checkRecoveryThreshold();
                return;
            }

            applyRecord(record, false);
            checkRecoveryThreshold();
        }

        /** Regra de ordem por partição: aplicar a próxima sequência ou retê-la. */
        private void applyRecord(SensorRecord record, boolean recovered) throws IOException {
            long sequence = record.getSeq();
            if (sequence <= lastSequence || retained.containsKey(sequence)) {
                if (!recovered) {
                    System.out.println("DUPLICADO: p" + partition + " seq=" + sequence);
                }
                return;
            }
            if (sequence == lastSequence + 1) {
                appendInOrder(record, recovered ? "RECUPERADO" : "APLICADO");
                drainRetained();
            } else {
                retained.put(sequence, record);
                System.out.println("EM ESPERA: p" + partition + " seq=" + sequence
                        + " (falta o registo " + (lastSequence + 1) + ")");
            }
        }

        private void appendInOrder(SensorRecord record, String label) throws IOException {
            file.append(record);
            lastSequence = record.getSeq();
            if ("RECUPERADO".equals(label)) {
                System.out.println("RECUPERADO: p" + partition + " seq=" + record.getSeq());
            } else {
                System.out.println("APLICADO: p" + partition + " " + record.toLine());
            }
        }

        /** Escreve em sequência todos os retidos que já não têm lacuna anterior. */
        private void drainRetained() throws IOException {
            while (!retained.isEmpty() && retained.firstKey() == lastSequence + 1) {
                Map.Entry<Long, SensorRecord> next = retained.pollFirstEntry();
                appendInOrder(next.getValue(), "APLICADO");
            }
        }

        private void checkRecoveryThreshold() {
            if (retained.size() > Wire.LIMITE_RETIDOS) {
                recoverMissingRange();
            }
        }

        /** Recupera até antes do primeiro registo retido, usando o endpoint anunciado no REG. */
        private void recoverMissingRange() {
            if (leaderHost == null || retained.isEmpty()) {
                return;
            }
            long from = lastSequence + 1;
            long to = retained.firstKey() - 1;
            if (from > to) {
                try {
                    drainRetained();
                } catch (IOException e) {
                    System.out.println("RECUPERAÇÃO FALHOU: " + e.getMessage());
                }
                return;
            }

            System.out.println("RECUPERAÇÃO: " + from + ".." + to
                    + " pedida a " + leaderHost + ":" + leaderPort);
            boolean finished = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(leaderHost, leaderPort), Wire.T_TCP_MS);
                socket.setSoTimeout(Wire.T_TCP_MS);
                try (BufferedReader input = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter output = new PrintWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    output.println("PEDIDO " + from + " " + to);
                    String line;
                    while ((line = input.readLine()) != null) {
                        if ("FIM".equals(line.trim())) {
                            finished = true;
                            break;
                        }
                        if (line.startsWith("ERRO ")) {
                            throw new IOException(line);
                        }
                        SensorRecord recovered = SensorRecord.fromLine(line);
                        if (recovered.getSeq() < from || recovered.getSeq() > to) {
                            throw new IOException("registo fora do intervalo pedido: " + line);
                        }
                        applyRecord(recovered, true);
                    }
                }
                if (!finished) {
                    throw new IOException("resposta TCP interrompida antes de FIM");
                }
                drainRetained();
            } catch (IOException | IllegalArgumentException e) {
                // Registos já escritos ficam persistidos; os restantes continuam retidos.
                System.out.println("RECUPERAÇÃO FALHOU: " + e.getMessage());
            }
        }
    }
}
