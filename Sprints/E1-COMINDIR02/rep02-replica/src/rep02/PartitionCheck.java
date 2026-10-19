package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Verifica integridade, partições, replicação e convergência dos ficheiros reunidos. */
public final class PartitionCheck {
    private static final Pattern LEADER_FILE = Pattern.compile("lider-p(\\d+)\\.txt");
    private static final Pattern REPLICA_FILE = Pattern.compile("replica-(.+)-p(\\d+)\\.txt");

    private PartitionCheck() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Uso: PartitionCheck <N> <pasta>");
            return;
        }
        try {
            int partitions = Integer.parseInt(args[0]);
            if (partitions < 1) {
                throw new IllegalArgumentException("N tem de ser >= 1");
            }
            Path directory = Paths.get(args[1]);
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("Pasta inexistente: " + directory);
            }
            new Checker(partitions, directory).run();
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Não foi possível verificar: " + e.getMessage());
        }
    }

    private static final class Checker {
        private final int partitionCount;
        private final Path directory;
        private final Partitioner partitioner;
        private final List<Path> leaderFiles = new ArrayList<>();
        private final List<Path>[] replicaFiles;
        private boolean allValid = true;
        private long totalPartitionRecords;
        private long totalLeaderRecords;

        @SuppressWarnings("unchecked")
        private Checker(int partitionCount, Path directory) throws IOException {
            this.partitionCount = partitionCount;
            this.directory = directory;
            this.partitioner = new Partitioner(partitionCount);
            this.replicaFiles = (List<Path>[]) new List<?>[partitionCount];
            for (int partition = 0; partition < partitionCount; partition++) {
                replicaFiles[partition] = new ArrayList<>();
            }
            try (Stream<Path> paths = Files.list(directory)) {
                paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString))
                        .forEach(this::classifyFile);
            }
        }

        private void classifyFile(Path path) {
            String name = path.getFileName().toString();
            Matcher leader = LEADER_FILE.matcher(name);
            if (leader.matches()) {
                leaderFiles.add(path);
                return;
            }
            Matcher replica = REPLICA_FILE.matcher(name);
            if (replica.matches()) {
                int partition = Integer.parseInt(replica.group(2));
                if (partition >= 0 && partition < partitionCount) {
                    replicaFiles[partition].add(path);
                } else {
                    fail("Ficheiro de réplica fora de N=" + partitionCount + ": " + name);
                }
            }
        }

        private void run() throws IOException {
            System.out.println("=== Verificação COMINDIR02 | N=" + partitionCount
                    + " | pasta=" + directory.toAbsolutePath() + " ===");
            for (Path leader : leaderFiles) {
                LoadedFile loaded = load(leader);
                totalLeaderRecords += loaded.records.size();
                Matcher matcher = LEADER_FILE.matcher(leader.getFileName().toString());
                if (matcher.matches() && Integer.parseInt(matcher.group(1)) >= partitionCount) {
                    fail("Ficheiro de líder fora do intervalo esperado: " + leader.getFileName());
                }
            }

            for (int partition = 0; partition < partitionCount; partition++) {
                checkPartition(partition);
            }

            boolean totalsMatch = totalLeaderRecords == totalPartitionRecords;
            System.out.println("TOTAL do sistema (ficheiros de líderes): " + totalLeaderRecords);
            System.out.println("Soma das partições verificadas: " + totalPartitionRecords);
            System.out.println("Totais coincidem: " + (totalsMatch ? "SIM" : "NÃO"));
            if (!totalsMatch) {
                allValid = false;
            }
            System.out.println("RESULTADO: " + (allValid ? "APROVADO" : "REVER ERROS ACIMA"));
        }

        private void checkPartition(int partition) throws IOException {
            String leaderName = "lider-p" + partition + ".txt";
            Path leaderPath = directory.resolve(leaderName);
            boolean leaderExists = Files.isRegularFile(leaderPath);
            if (!leaderExists) {
                fail("p" + partition + ": falta " + leaderName);
            }
            LoadedFile leader = leaderExists ? load(leaderPath) : LoadedFile.empty();
            boolean leaderSequenceValid = checkSequence(leader, leaderName);
            boolean sensorPartitionValid = true;
            Set<String> sensors = new HashSet<>();
            for (int i = 0; i < leader.records.size(); i++) {
                SensorRecord record = leader.records.get(i);
                sensors.add(record.getSensorId());
                int actual = partitioner.of(record.getSensorId());
                if (actual != partition) {
                    sensorPartitionValid = false;
                    fail("p" + partition + ": " + leaderName + " seq=" + record.getSeq()
                            + " tem sensor " + record.getSensorId() + " (esperado p" + actual + ")");
                }
            }
            if (!leaderSequenceValid || !leader.valid || !sensorPartitionValid) {
                allValid = false;
            }

            totalPartitionRecords += leader.records.size();
            System.out.println("\n[p" + partition + "] líder=" + leader.records.size()
                    + " registos | sensores distintos=" + sensors.size()
                    + " | seq válida=" + (leaderSequenceValid && leader.valid)
                    + " | sensores na partição=" + sensorPartitionValid);

            List<Path> replicas = replicaFiles[partition];
            replicas.sort(Comparator.comparing(path -> path.getFileName().toString()));
            System.out.println("  réplicas encontradas=" + replicas.size() + " (esperado R=2)");
            if (replicas.size() != 2) {
                fail("p" + partition + ": encontradas " + replicas.size() + " réplicas; esperado 2");
            }
            for (Path replicaPath : replicas) {
                LoadedFile replica = load(replicaPath);
                String name = replicaPath.getFileName().toString();
                boolean sequenceValid = checkSequence(replica, name);
                boolean prefix = isPrefix(replica.lines, leader.lines);
                long missing = leader.records.size() - replica.records.size();
                boolean valid = replica.valid && sequenceValid && prefix && missing >= 0;
                if (!valid) {
                    fail(name + ": não é prefixo válido do líder");
                }
                String relation = prefix && missing == 0 ? "IGUAL"
                        : prefix && missing > 0 ? "PREFIXO" : "DIVERGENTE";
                System.out.println("  " + name + ": " + replica.records.size() + " registos | "
                        + relation + " | em falta=" + Math.max(0, missing)
                        + " | seq válida=" + (sequenceValid && replica.valid));
            }
        }

        private boolean checkSequence(LoadedFile file, String name) {
            boolean valid = file.valid;
            long expected = 1;
            for (SensorRecord record : file.records) {
                if (record.getSeq() != expected) {
                    fail(name + ": sequência inválida na posição " + expected
                            + " (encontrado seq=" + record.getSeq() + ")");
                    valid = false;
                }
                expected++;
            }
            return valid;
        }

        private static boolean isPrefix(List<String> candidate, List<String> complete) {
            if (candidate.size() > complete.size()) {
                return false;
            }
            for (int i = 0; i < candidate.size(); i++) {
                if (!candidate.get(i).equals(complete.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private LoadedFile load(Path path) throws IOException {
            List<String> lines = new ArrayList<>();
            List<SensorRecord> records = new ArrayList<>();
            boolean valid = true;
            int lineNumber = 0;
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (rawLine.isBlank()) {
                    continue;
                }
                String line = rawLine.trim();
                lines.add(line);
                try {
                    records.add(SensorRecord.fromLine(line));
                } catch (IllegalArgumentException e) {
                    fail(path.getFileName() + ": linha " + lineNumber + " inválida: " + e.getMessage());
                    valid = false;
                }
            }
            return new LoadedFile(lines, records, valid);
        }

        private void fail(String message) {
            allValid = false;
            System.out.println("  ERRO: " + message);
        }
    }

    private static final class LoadedFile {
        private final List<String> lines;
        private final List<SensorRecord> records;
        private final boolean valid;

        private LoadedFile(List<String> lines, List<SensorRecord> records, boolean valid) {
            this.lines = lines;
            this.records = records;
            this.valid = valid;
        }

        private static LoadedFile empty() {
            return new LoadedFile(new ArrayList<>(), new ArrayList<>(), false);
        }
    }
}
