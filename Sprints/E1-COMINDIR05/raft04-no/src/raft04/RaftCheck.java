package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RaftCheck {
    private static final Pattern EVENT_FILE = Pattern.compile("eventos-p(\\d+)-n(\\d+)\\.txt");
    private static final Pattern NODE_FILE = Pattern.compile("no-p(\\d+)-n(\\d+)\\.txt");
    private static final Pattern HB_FILE = Pattern.compile("hb-p(\\d+)-n(\\d+)\\.txt");
    private static final Pattern STATE_FILE = Pattern.compile("estado-p(\\d+)-n(\\d+)\\.txt");
    private static final Pattern TERM = Pattern.compile("(?:^|;)termo=(\\d+)");
    private static final Pattern NODE = Pattern.compile("n(\\d+)");
    private static final Pattern VOTE_FOR = Pattern.compile("candidato=n(\\d+)");

    private static final class Event {
        final long time;
        final int partition;
        final int node;
        final String type;
        final String line;
        final long term;
        Event(long time, int partition, int node, String type, String line, long term) {
            this.time = time;
            this.partition = partition;
            this.node = node;
            this.type = type;
            this.line = line;
            this.term = term;
        }
    }

    private static final class RecordLocation {
        final Path path;
        final Map<Long, SensorRecord> records = new TreeMap<>();
        RecordLocation(Path path) { this.path = path; }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Uso: RaftCheck <N> <pasta> [falhas.txt]");
            return;
        }
        try {
            int n = Integer.parseInt(args[0]);
            Path folder = Path.of(args[1]);
            if (n <= 0 || !Files.isDirectory(folder)) throw new IllegalArgumentException("N ou pasta inválidos");
            run(n, folder, args.length == 3 ? Path.of(args[2]) : null);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("RaftCheck: " + e.getMessage());
        }
    }

    private static void run(int n, Path folder, Path failures) throws IOException {
        Map<Integer, List<Event>> events = new TreeMap<>();
        Map<Integer, List<RecordLocation>> logs = new TreeMap<>();
        Map<Integer, List<long[]>> heartbeats = new TreeMap<>();
        loadEvents(n, folder, events);
        loadRecords(n, folder, logs);
        loadHeartbeats(n, folder, heartbeats);
        long invalidStates = checkStates(n, folder, events);

        long duplicateLeaders = 0;
        long decreasingTerms = 0;
        System.out.println("========== RAFT CHECK ==========");
        for (int partition = 0; partition < n; partition++) {
            List<Event> partitionEvents = events.getOrDefault(partition, Collections.emptyList());
            partitionEvents.sort((a, b) -> Long.compare(a.time, b.time));
            Map<Long, List<Event>> byTerm = new TreeMap<>();
            long firstStart = Long.MAX_VALUE;
            long firstLeader = Long.MAX_VALUE;
            Map<Integer, Long> lastSeenTerm = new HashMap<>();
            for (Event event : partitionEvents) {
                if ("ARRANQUE".equals(event.type)) firstStart = Math.min(firstStart, event.time);
                if ("CANDIDATO".equals(event.type)) {
                    firstStart = Math.min(firstStart, event.time);
                    byTerm.computeIfAbsent(event.term, ignored -> new ArrayList<>()).add(event);
                } else if ("LIDER".equals(event.type)) {
                    byTerm.computeIfAbsent(event.term, ignored -> new ArrayList<>()).add(event);
                    firstLeader = Math.min(firstLeader, event.time);
                } else if ("VOTO".equals(event.type) && event.line.contains("concedido")) {
                    byTerm.computeIfAbsent(event.term, ignored -> new ArrayList<>()).add(event);
                }
                Matcher termChange = Pattern.compile("(\\d+)->(\\d+)").matcher(event.line);
                boolean invalidTransition = false;
                if (termChange.find()) {
                    long oldTerm = Long.parseLong(termChange.group(1));
                    long newTerm = Long.parseLong(termChange.group(2));
                    invalidTransition = newTerm <= oldTerm;
                    if (invalidTransition) decreasingTerms++;
                }
                if (event.term >= 0) {
                    long previous = lastSeenTerm.getOrDefault(event.node, -1L);
                    if (!invalidTransition && previous >= 0 && event.term < previous) decreasingTerms++;
                    lastSeenTerm.put(event.node, Math.max(previous, event.term));
                }
            }
            System.out.println("p" + partition + ":");
            for (Map.Entry<Long, List<Event>> entry : byTerm.entrySet()) {
                long term = entry.getKey();
                List<Event> termEvents = entry.getValue();
                Set<Integer> leaders = new TreeSet<>();
                Set<Integer> candidates = new TreeSet<>();
                Map<Integer, Set<Integer>> votesByCandidate = new TreeMap<>();
                long candidateAt = Long.MAX_VALUE;
                long leaderAt = Long.MAX_VALUE;
                for (Event event : termEvents) {
                    if ("CANDIDATO".equals(event.type)) {
                        candidates.add(event.node);
                        votesByCandidate.computeIfAbsent(event.node, ignored -> new TreeSet<>()).add(event.node);
                        candidateAt = Math.min(candidateAt, event.time);
                    } else if ("VOTO".equals(event.type)) {
                        Matcher target = VOTE_FOR.matcher(event.line);
                        if (target.find()) {
                            votesByCandidate.computeIfAbsent(Integer.parseInt(target.group(1)),
                                    ignored -> new TreeSet<>()).add(event.node);
                        }
                    } else if ("LIDER".equals(event.type)) {
                        leaders.add(event.node);
                        leaderAt = Math.min(leaderAt, event.time);
                    }
                }
                for (int candidate : candidates) {
                    votesByCandidate.computeIfAbsent(candidate, ignored -> new TreeSet<>()).add(candidate);
                }
                if (leaders.isEmpty()) {
                    System.out.println("  termo " + term + ": candidatos=" + candidates + ", votos=" + votesByCandidate
                            + " — voto dividido (ou eleição sem maioria)");
                } else {
                    long duration = candidateAt == Long.MAX_VALUE ? 0 : leaderAt - candidateAt;
                    System.out.println("  termo " + term + ": candidatos=" + candidates + ", votos=" + votesByCandidate
                            + ", líder(es)=" + leaders + ", duração=" + duration + " ms");
                    if (leaders.size() > 1) duplicateLeaders++;
                }
            }
            if (firstLeader != Long.MAX_VALUE && firstStart != Long.MAX_VALUE) {
                System.out.println("  tempo até ao primeiro líder: " + (firstLeader - firstStart) + " ms");
            } else {
                System.out.println("  sem líder observado");
            }
            reportAgreement(partition, logs.getOrDefault(partition, Collections.emptyList()));
        }
        System.out.println("Líderes múltiplos no mesmo termo: " + duplicateLeaders);
        System.out.println("Termos não crescentes observados: " + decreasingTerms
                + " | estado persistido inconsistente: " + invalidStates);
        reportLostReads(n, folder, logs);
        reportTruncations(events, logs);
        reportMonitors(folder);
        if (failures != null) reportFailures(n, folder, failures, events, heartbeats);
        if (duplicateLeaders != 0 || decreasingTerms != 0 || invalidStates != 0) {
            System.out.println("SEGURANÇA: ERRO");
            System.exit(1);
        }
        System.out.println("SEGURANÇA: OK");
    }

    private static void loadEvents(int n, Path folder, Map<Integer, List<Event>> all) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Matcher matcher = EVENT_FILE.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                int partition = Integer.parseInt(matcher.group(1));
                int node = Integer.parseInt(matcher.group(2));
                if (partition >= n) continue;
                List<Event> out = all.computeIfAbsent(partition, ignored -> new ArrayList<>());
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] fields = line.split(";", 4);
                    if (fields.length < 3) continue;
                    long time;
                    try { time = Long.parseLong(fields[0]); } catch (NumberFormatException e) { continue; }
                    String type = fields[1];
                    Matcher term = TERM.matcher(line);
                    long number = -1;
                    if (term.find()) number = Long.parseLong(term.group(1));
                    else {
                        Matcher transition = Pattern.compile("(\\d+)->(\\d+)").matcher(line);
                        if (transition.find()) number = Long.parseLong(transition.group(2));
                    }
                    out.add(new Event(time, partition, node, type, line, number));
                }
            }
        }
    }

    private static void loadRecords(int n, Path folder, Map<Integer, List<RecordLocation>> all) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Matcher matcher = NODE_FILE.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                int partition = Integer.parseInt(matcher.group(1));
                if (partition >= n) continue;
                RecordLocation location = new RecordLocation(path);
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    try {
                        SensorRecord record = SensorRecord.fromLine(line);
                        location.records.put(record.getSeq(), record);
                    } catch (IllegalArgumentException ignored) {
                        System.out.println("Registo inválido em " + path.getFileName() + ": " + line);
                    }
                }
                all.computeIfAbsent(partition, ignored -> new ArrayList<>()).add(location);
            }
        }
    }

    private static void loadHeartbeats(int n, Path folder, Map<Integer, List<long[]>> all) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Matcher matcher = HB_FILE.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                int partition = Integer.parseInt(matcher.group(1));
                if (partition >= n) continue;
                List<long[]> rows = all.computeIfAbsent(partition, ignored -> new ArrayList<>());
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] fields = line.split(";");
                    if (fields.length >= 5) {
                        try { rows.add(new long[] { Long.parseLong(fields[0]), Long.parseLong(fields[1]) }); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            }
        }
    }

    private static long checkStates(int n, Path folder, Map<Integer, List<Event>> events) throws IOException {
        Map<String, Long> maximumTerms = new HashMap<>();
        for (List<Event> partitionEvents : events.values()) {
            for (Event event : partitionEvents) {
                if (event.term >= 0) {
                    maximumTerms.merge(event.partition + ":" + event.node, event.term, Math::max);
                }
            }
        }
        long invalid = 0;
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Matcher matcher = STATE_FILE.matcher(path.getFileName().toString());
                if (!matcher.matches() || Integer.parseInt(matcher.group(1)) >= n) continue;
                String[] fields = Files.readString(path, StandardCharsets.UTF_8).trim().split(";", -1);
                if (fields.length != 2) {
                    System.out.println("Estado inválido: " + path.getFileName());
                    invalid++;
                    continue;
                }
                int partition = Integer.parseInt(matcher.group(1));
                int node = Integer.parseInt(matcher.group(2));
                long term = Long.parseLong(fields[0]);
                long observed = maximumTerms.getOrDefault(partition + ":" + node, 0L);
                if (term < observed) {
                    System.out.println("TERMO PERSISTIDO ATRASADO: " + path.getFileName()
                            + " estado=" + term + " eventos=" + observed);
                    invalid++;
                }
            }
        }
        return invalid;
    }

    private static void reportAgreement(int partition, List<RecordLocation> nodes) {
        if (nodes.size() < 2) {
            System.out.println("  coincidência final: menos de dois ficheiros de nó");
            return;
        }
        long min = Long.MAX_VALUE;
        for (RecordLocation node : nodes) {
            if (!node.records.isEmpty()) min = Math.min(min, node.records.keySet().stream().mapToLong(Long::longValue).max().orElse(0));
            else min = 0;
        }
        List<Long> differences = new ArrayList<>();
        for (long seq = 1; seq <= min; seq++) {
            SensorRecord first = nodes.get(0).records.get(seq);
            boolean equal = first != null;
            for (int i = 1; i < nodes.size(); i++) {
                SensorRecord other = nodes.get(i).records.get(seq);
                if (first == null || other == null || first.getTerm() != other.getTerm()
                        || !first.toLine().equals(other.toLine())) equal = false;
            }
            if (!equal) differences.add(seq);
        }
        System.out.println("  coincidência final até seq " + (min == Long.MAX_VALUE ? 0 : min)
                + ": divergências=" + differences);
    }

    private static void reportLostReads(int n, Path folder, Map<Integer, List<RecordLocation>> logs) throws IOException {
        Path producer = folder.resolve("produtor.txt");
        if (!Files.isRegularFile(producer)) return;
        Map<String, Integer> available = new HashMap<>();
        Set<String> uniqueRecords = new HashSet<>();
        for (List<RecordLocation> nodes : logs.values()) {
            for (RecordLocation node : nodes) for (SensorRecord record : node.records.values()) {
                String partition = node.path.getFileName().toString().replaceAll("no-p(\\d+)-n\\d+\\.txt", "$1");
                String identity = partition + ";" + record.getSeq() + ";" + record.getTerm();
                if (uniqueRecords.add(identity)) {
                    available.merge(partition + ";" + record.getSensorId() + ";"
                            + Double.toString(record.getTemperature()), 1, Integer::sum);
                }
            }
        }
        long sent = 0, lost = 0;
        for (String line : Files.readAllLines(producer, StandardCharsets.UTF_8)) {
            String[] fields = line.split(";", -1);
            if (fields.length != 4) continue;
            sent++;
            String key = fields[1].replace("p", "") + ";" + fields[2] + ";" + fields[3];
            int count = available.getOrDefault(key, 0);
            if (count == 0) lost++;
            else available.put(key, count - 1);
        }
        System.out.println("Leituras de produtor.txt: " + sent + " | não encontradas no registo final: " + lost);
    }

    private static void reportTruncations(Map<Integer, List<Event>> events,
            Map<Integer, List<RecordLocation>> logs) {
        long count = 0;
        long reappeared = 0;
        for (Map.Entry<Integer, List<Event>> entry : events.entrySet()) {
            for (Event event : entry.getValue()) {
                if (event.line.contains(";TRUNCADO;")) {
                    count++;
                    System.out.println("TRUNCADO p" + entry.getKey() + ": " + event.line);
                    int marker = event.line.indexOf(";registos=");
                    if (marker >= 0) {
                        String discarded = event.line.substring(marker + ";registos=".length());
                        for (String item : discarded.split(",")) {
                            String[] fields = item.split(":", -1);
                            if (fields.length != 4) continue;
                            try {
                                long oldSeq = Long.parseLong(fields[0]);
                                String sensor = fields[2];
                                double temperature = Double.parseDouble(fields[3]);
                                boolean found = false;
                                for (RecordLocation node : logs.getOrDefault(entry.getKey(), Collections.emptyList())) {
                                    for (SensorRecord record : node.records.values()) {
                                        if (record.getSeq() != oldSeq && record.getSensorId().equals(sensor)
                                                && Double.compare(record.getTemperature(), temperature) == 0) {
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (found) break;
                                }
                                if (found) reappeared++;
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                }
            }
        }
        System.out.println("Eventos TRUNCADO: " + count + " | registos identificáveis com outro seq: " + reappeared);
    }

    private static void reportMonitors(Path folder) throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = path.getFileName().toString();
                if (!name.startsWith("monitor-") || !name.endsWith(".txt")) continue;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] fields = line.split(";", 3);
                    if (fields.length >= 2) counts.merge(fields[1], 1, Integer::sum);
                }
            }
        }
        if (!counts.isEmpty()) System.out.println("Eventos dos monitores: " + counts);
    }

    private static void reportFailures(int n, Path folder, Path failures,
            Map<Integer, List<Event>> events, Map<Integer, List<long[]>> heartbeats) throws IOException {
        Path file = Files.isRegularFile(failures) ? failures : folder.resolve(failures);
        if (!Files.isRegularFile(file)) {
            System.out.println("Ficheiro de falhas não encontrado: " + failures);
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] fields = line.trim().split(";");
            if (fields.length != 3) continue;
            Matcher p = Pattern.compile("p(\\d+)").matcher(fields[0]);
            Matcher node = NODE.matcher(fields[1]);
            if (!p.matches() || !node.matches()) continue;
            int partition = Integer.parseInt(p.group(1));
            if (partition >= n) continue;
            long failureAt;
            try { failureAt = Long.parseLong(fields[2]); } catch (NumberFormatException e) { continue; }
            long previousTerm = -1;
            for (Event event : events.getOrDefault(partition, Collections.emptyList())) {
                if (event.node == Integer.parseInt(node.group(1)) && event.time <= failureAt && event.term >= 0) {
                    previousTerm = Math.max(previousTerm, event.term);
                }
            }
            long restored = Long.MAX_VALUE;
            for (long[] hb : heartbeats.getOrDefault(partition, Collections.emptyList())) {
                if (hb[0] >= failureAt && hb[1] > previousTerm) restored = Math.min(restored, hb[0]);
            }
            if (restored == Long.MAX_VALUE) {
                System.out.println("Falha " + line + ": sem LOTE posterior de termo superior");
            } else {
                long reads = 0;
                Path producer = folder.resolve("produtor.txt");
                if (Files.isRegularFile(producer)) {
                    for (String sent : Files.readAllLines(producer, StandardCharsets.UTF_8)) {
                        String[] readFields = sent.split(";", -1);
                        if (readFields.length != 4) continue;
                        try {
                            if (Integer.parseInt(readFields[1].replace("p", "")) == partition
                                    && Long.parseLong(readFields[0]) >= failureAt
                                    && Long.parseLong(readFields[0]) < restored) reads++;
                        } catch (NumberFormatException ignored) { }
                    }
                }
                System.out.println("Falha " + line + ": indisponibilidade=" + (restored - failureAt)
                        + " ms | IN enviados nesse intervalo=" + reads);
            }
        }
    }
}
