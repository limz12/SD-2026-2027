package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CausalCheck {
    private static final Pattern NODE_FILE = Pattern.compile("no-p(\\d+)-n(\\d+)\\.txt");

    private static final class Entry {
        final int partition;
        final SensorRecord record;
        Entry(int partition, SensorRecord record) {
            this.partition = partition;
            this.record = record;
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Uso: CausalCheck <N> <pasta>");
            return;
        }
        try {
            int n = Integer.parseInt(args[0]);
            Path folder = Path.of(args[1]);
            if (n <= 0 || !Files.isDirectory(folder)) throw new IllegalArgumentException("N ou pasta inválidos");
            run(n, folder);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("CausalCheck: " + e.getMessage());
        }
    }

    private static void run(int n, Path folder) throws IOException {
        List<Entry> all = new ArrayList<>();
        Map<String, SensorRecord> identities = new HashMap<>();
        long invalid = 0;
        long sequenceErrors = 0;
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Matcher name = NODE_FILE.matcher(path.getFileName().toString());
                if (!name.matches()) continue;
                int partition = Integer.parseInt(name.group(1));
                if (partition >= n) continue;
                long expected = 1;
                int lineNumber = 0;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    lineNumber++;
                    if (line.isBlank()) continue;
                    try {
                        SensorRecord record = SensorRecord.fromLine(line, n);
                        all.add(new Entry(partition, record));
                        if (record.getSeq() != expected) {
                            System.out.println("SEQUÊNCIA INVÁLIDA: " + path.getFileName() + ":" + lineNumber
                                    + " seq=" + record.getSeq() + " esperado=" + expected);
                            sequenceErrors++;
                        }
                        expected = record.getSeq() + 1;
                        if (record.getVector().get(partition) != record.getSeq()) {
                            System.out.println("VIOLAÇÃO V[" + partition + "] == seq: " + path.getFileName()
                                    + ":" + lineNumber + " V=" + record.getVector() + " seq=" + record.getSeq());
                            invalid++;
                        }
                        String key = partition + ":" + record.getSeq() + ":" + record.getTerm();
                        SensorRecord previous = identities.putIfAbsent(key, record);
                        if (previous != null && (!previous.getVector().equals(record.getVector())
                                || previous.getLamport() != record.getLamport()
                                || !previous.toLine().equals(record.toLine()))) {
                            System.out.println("DIVERGÊNCIA CAUSAL NO MESMO TERMO: p" + partition
                                    + " seq=" + record.getSeq() + " termo=" + record.getTerm());
                            invalid++;
                        }
                    } catch (IllegalArgumentException e) {
                        System.out.println("REGISTO INVÁLIDO: " + path.getFileName() + ":" + lineNumber
                                + " (" + e.getMessage() + ")");
                        invalid++;
                    }
                }
            }
        }

        List<Entry> unique = new ArrayList<>();
        Map<String, Entry> seen = new HashMap<>();
        for (Entry entry : all) {
            String key = entry.partition + ":" + entry.record.getSeq() + ":" + entry.record.getTerm();
            if (seen.putIfAbsent(key, entry) == null) unique.add(entry);
        }
        long lamportViolations = 0;
        for (Entry earlier : unique) {
            for (Entry later : unique) {
                if (earlier.record.getVector().compare(later.record.getVector()) == VectorClock.Order.BEFORE
                        && earlier.record.getLamport() >= later.record.getLamport()) {
                    System.out.println("VIOLAÇÃO DE LAMPORT: " + earlier.record.toLine()
                            + " precede causalmente " + later.record.toLine());
                    lamportViolations++;
                }
            }
        }

        Map<Integer, Long> counts = new TreeMap<>();
        for (Entry entry : unique) counts.merge(entry.partition, 1L, Long::sum);
        System.out.println("========== CAUSAL CHECK ==========");
        System.out.println("Registos únicos: " + unique.size() + " | por partição: " + counts);
        System.out.println("Registos inválidos/V[k]!=seq: " + invalid);
        System.out.println("Sequências não contíguas: " + sequenceErrors);
        System.out.println("Violações de Lamport: " + lamportViolations);
        System.out.println(invalid + sequenceErrors + lamportViolations == 0 ? "RESULTADO: OK" : "RESULTADO: ERROS");
        if (invalid + sequenceErrors + lamportViolations > 0) System.exit(1);
    }
}
