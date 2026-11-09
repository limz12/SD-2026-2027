
package raft04;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.time.LocalDateTime;
import java.time.ZoneId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verificador da COMINDIR05.
 *
 * Execução:
 *
 * RaftCheck <N> <pasta> [falhas.txt]
 *
 * Analisa:
 *
 * no-p<k>-n<i>.txt
 * estado-p<k>-n<i>.txt
 * hb-p<k>-n<i>.txt
 * eventos-p<k>-n<i>.txt
 * monitor-*.txt
 * produtor.txt
 *
 * Opcionalmente, falhas.txt:
 *
 * p<k>;n<i>;instante
 *
 * O verificador não altera os ficheiros analisados.
 *
 * Para auditar TRUNCADO, lê também descartados-p<k>-n<i>.txt,
 * escrito pelo nó antes da substituição atómica do log.
 */
public final class RaftCheck {

    private static final Pattern NODE_FILE =
        Pattern.compile(
            "(no|estado|hb|eventos)-p(\\d+)-n(\\d+)\\.txt"
        );

    private static final Pattern PART =
        Pattern.compile("p(\\d+)");

    private static final Pattern NODE =
        Pattern.compile("n(\\d+)");

    private static final Pattern TRANSITION =
        Pattern.compile("(\\d+)->(\\d+)");

    private static final Pattern VOTE_LIST =
        Pattern.compile("\\[(.*?)\\]");

    private final int N;
    private final Path pasta;

    private final Map<Integer, PartitionData> particoes =
        new TreeMap<>();

    private final List<Reading> leituras =
        new ArrayList<>();

    private final List<Failure> falhas =
        new ArrayList<>();

    private final List<MonitorEvent> monitores =
        new ArrayList<>();

    private final List<String> avisos =
        new ArrayList<>();

    private RaftCheck(int n, Path pasta) {

        this.N = n;
        this.pasta = pasta;

        for (int k = 0; k < n; k++) {
            particoes.put(
                k,
                new PartitionData(k)
            );
        }
    }

    /* =====================================================
     * Estruturas de dados
     * ===================================================== */

    private static final class Record {

        final long seq;
        final long term;

        final String sensor;
        final String temperature;
        final String timestamp;

        final String line;

        Record(
                long seq,
                long term,
                String sensor,
                String temperature,
                String timestamp,
                String line) {

            this.seq = seq;
            this.term = term;
            this.sensor = sensor;
            this.temperature = temperature;
            this.timestamp = timestamp;
            this.line = line;
        }

        String key() {
            return readingKey(sensor, temperature);
        }
    }

    private static final class NodeData {

        final int k;
        final int i;

        final TreeMap<Long, Record> records =
            new TreeMap<>();

        final List<Event> events =
            new ArrayList<>();

        final List<HB> heartbeats =
            new ArrayList<>();

        boolean logExists;
        boolean stateExists;
        boolean eventsExist;
        boolean hbExists;

        long persistedTerm = -1;
        int votedFor = 0;

        NodeData(int k, int i) {
            this.k = k;
            this.i = i;
        }

        long lastSeq() {
            return records.isEmpty()
                ? 0
                : records.lastKey();
        }

        long lastTerm() {
            return records.isEmpty()
                ? 0
                : records.lastEntry().getValue().term;
        }
    }

    private static final class Event {

        final long time;
        final int k;
        final int node;
        final int order;
        final String type;
        final String raw;

        final Map<String, String> fields =
            new HashMap<>();

        Event(
                long time,
                int k,
                int node,
                int order,
                String type,
                String raw) {

            this.time = time;
            this.k = k;
            this.node = node;
            this.order = order;
            this.type = type;
            this.raw = raw;
        }

        long term() {
            return numeric(fields.get("termo"), -1);
        }

        long longField(String name, long fallback) {
            return numeric(fields.get(name), fallback);
        }

        String field(String name) {
            return fields.get(name);
        }
    }

    private static final class HB {

        final int k;
        final int node;

        final long term;
        final long hb;
        final long time;
        final long lastSeq;
        final long lastTerm;

        final int count;
        final int bytes;

        HB(
                int k,
                int node,
                long term,
                long hb,
                long time,
                long lastSeq,
                long lastTerm,
                int count,
                int bytes) {

            this.k = k;
            this.node = node;
            this.term = term;
            this.hb = hb;
            this.time = time;
            this.lastSeq = lastSeq;
            this.lastTerm = lastTerm;
            this.count = count;
            this.bytes = bytes;
        }
    }

    private static final class Reading {

        final long time;
        final int k;
        final String sensor;
        final String temperature;

        Reading(
                long time,
                int k,
                String sensor,
                String temperature) {

            this.time = time;
            this.k = k;
            this.sensor = sensor;
            this.temperature = temperature;
        }

        String key() {
            return readingKey(sensor, temperature);
        }
    }

    /** Registo descartado, preservado antes da truncagem. */
    private static final class Discarded {
        final String auditId;
        final int k;
        final int node;
        final long oldSeq;
        final long oldTerm;
        final String sensor;
        final String temperature;
        final String timestamp;

        Discarded(String auditId, int k, int node, long oldSeq, long oldTerm,
                  String sensor, String temperature, String timestamp) {
            this.auditId = auditId;
            this.k = k;
            this.node = node;
            this.oldSeq = oldSeq;
            this.oldTerm = oldTerm;
            this.sensor = sensor;
            this.temperature = temperature;
            this.timestamp = timestamp;
        }

        String identity() {
            return readingKey(sensor, temperature);
        }
    }

    private final Map<String, List<Discarded>> discardedByAudit = new HashMap<>();

    private static final class Failure {

        final int k;
        final int node;
        final long time;

        Failure(int k, int node, long time) {
            this.k = k;
            this.node = node;
            this.time = time;
        }
    }

    private static final class MonitorEvent {

        final String source;
        final long time;
        final String type;
        final int k;

        MonitorEvent(
                String source,
                long time,
                String type,
                int k) {

            this.source = source;
            this.time = time;
            this.type = type;
            this.k = k;
        }
    }

    private static final class TermData {

        final long term;

        final Set<Integer> candidates =
            new TreeSet<>();

        final Set<Integer> leaders =
            new TreeSet<>();

        final Map<Integer, Set<Integer>> votes =
            new TreeMap<>();

        final Map<Integer, Set<Integer>> leaderVotes =
            new TreeMap<>();

        long firstCandidate = Long.MAX_VALUE;
        long firstLeader = Long.MAX_VALUE;

        TermData(long term) {
            this.term = term;
        }

        void addVote(int candidate, int voter) {

            votes.computeIfAbsent(
                candidate,
                x -> new TreeSet<>()
            ).add(voter);
        }
    }

    private static final class PartitionData {

        final int k;

        final Map<Integer, NodeData> nodes =
            new TreeMap<>();

        final TreeMap<Long, TermData> terms =
            new TreeMap<>();

        final List<HB> heartbeats =
            new ArrayList<>();

        final List<Event> events =
            new ArrayList<>();

        long firstStartup = Long.MAX_VALUE;

        PartitionData(int k) {

            this.k = k;

            for (int i = 1; i <= 3; i++) {
                nodes.put(i, new NodeData(k, i));
            }
        }

        TermData term(long t) {

            return terms.computeIfAbsent(
                t,
                TermData::new
            );
        }
    }

    /* =====================================================
     * Funções auxiliares
     * ===================================================== */

    private static long numeric(
            String text,
            long fallback) {

        if (text == null) {
            return fallback;
        }

        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int integer(
            String text,
            int fallback) {

        long value = numeric(text, fallback);

        if (value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            return fallback;
        }

        return (int) value;
    }

    private static String readingKey(
            String sensor,
            String temperature) {

        String normalized = temperature.trim();

        try {

            normalized = new BigDecimal(normalized)
                .stripTrailingZeros()
                .toPlainString();

        } catch (NumberFormatException e) {
            // Manter a representação original.
        }

        return sensor + "\u0000" + normalized;
    }

    private static String duration(long ms) {

        if (ms < 0) {
            return "indisponível";
        }

        return ms + " ms ("
                + String.format(
                    java.util.Locale.ROOT,
                    "%.3f",
                    ms / 1000.0
                )
                + " s)";
    }

    private void warn(String message) {
        avisos.add(message);
    }

    private static List<String> lines(Path path)
            throws IOException {

        return Files.readAllLines(
            path,
            StandardCharsets.UTF_8
        );
    }

    private static Long parseRecordTime(String timestamp) {

        try {

            return LocalDateTime.parse(timestamp)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        } catch (RuntimeException e) {

            return null;
        }
    }

    /* =====================================================
     * Leitura dos ficheiros
     * ===================================================== */

    private void readAll(Path failureFile)
            throws IOException {

        readNodes();
        readDiscarded();
        readProducer();
        readMonitors();

        if (failureFile != null) {
            readFailures(failureFile);
        }

        buildTerms();
    }

    private void readNodes() throws IOException {

        for (PartitionData p : particoes.values()) {

            for (NodeData node : p.nodes.values()) {

                readLog(node);
                readState(node);
                readEvents(node, p);
                readHB(node, p);
            }

            p.events.sort(
                Comparator.comparingLong(
                    (Event e) -> e.time
                )
                .thenComparingInt(e -> e.node)
                .thenComparingInt(e -> e.order)
            );

            p.heartbeats.sort(
                Comparator.comparingLong(
                    (HB h) -> h.time
                )
                .thenComparingInt(h -> h.node)
                .thenComparingLong(h -> h.hb)
            );
        }
    }

    private void readLog(NodeData node)
            throws IOException {

        Path file = pasta.resolve(
            "no-p" + node.k
            + "-n" + node.i + ".txt"
        );

        if (!Files.exists(file)) {
            return;
        }

        node.logExists = true;

        int lineNumber = 0;

        for (String line : lines(file)) {

            lineNumber++;

            if (line.isBlank()) {
                continue;
            }

            String[] f = line.trim().split(";", -1);

            if (f.length != 7) {

                warn(
                    file.getFileName()
                    + ":" + lineNumber
                    + " — registo sem 7 campos"
                );

                continue;
            }

            try {

                long seq = Long.parseLong(f[0]);
                long term = Long.parseLong(f[1]);

                // Validar os restantes campos numéricos.
                Double.parseDouble(f[3]);
                Long.parseLong(f[5]);

                if (f[6].isBlank()) {
                    throw new IllegalArgumentException(
                        "Vetor vazio"
                    );
                }

                if (seq < 1 || term < 0) {
                    throw new IllegalArgumentException(
                        "Sequência ou termo inválido"
                    );
                }

                Record r = new Record(
                    seq,
                    term,
                    f[2],
                    f[3],
                    f[4],
                    line.trim()
                );

                if (node.records.putIfAbsent(seq, r)
                        != null) {

                    warn(
                        file.getFileName()
                        + " — sequência duplicada: "
                        + seq
                    );
                }

            } catch (RuntimeException e) {

                warn(
                    file.getFileName()
                    + ":" + lineNumber
                    + " — registo inválido: "
                    + e.getMessage()
                );
            }
        }

        long expected = 1;

        for (long seq : node.records.keySet()) {

            if (seq != expected) {

                warn(
                    file.getFileName()
                    + " — lacuna: esperado "
                    + expected + ", encontrado " + seq
                );

            }

            expected = seq + 1;
        }
    }

    private void readState(NodeData node)
            throws IOException {

        Path file = pasta.resolve(
            "estado-p" + node.k
            + "-n" + node.i + ".txt"
        );

        if (!Files.exists(file)) {
            return;
        }

        node.stateExists = true;

        String content = Files.readString(
            file,
            StandardCharsets.UTF_8
        ).trim();

        String[] f = content.split(";", -1);

        if (f.length < 2) {

            warn(
                file.getFileName()
                + " — estado inválido"
            );

            return;
        }

        node.persistedTerm = numeric(f[0], -1);
        node.votedFor = integer(f[1], 0);
    }

    private void readEvents(
            NodeData node,
            PartitionData p) throws IOException {

        Path file = pasta.resolve(
            "eventos-p" + node.k
            + "-n" + node.i + ".txt"
        );

        if (!Files.exists(file)) {
            return;
        }

        node.eventsExist = true;

        int order = 0;

        for (String line : lines(file)) {

            order++;

            if (line.isBlank()) {
                continue;
            }

            String[] f = line.split(";", -1);

            if (f.length < 2) {
                continue;
            }

            long time = numeric(f[0], -1);

            if (time < 0) {

                warn(
                    file.getFileName()
                    + ":" + order
                    + " — instante inválido"
                );

                continue;
            }

            Event e = new Event(
                time,
                node.k,
                node.i,
                order,
                f[1].trim(),
                line
            );

            for (int j = 2; j < f.length; j++) {

                int pos = f[j].indexOf('=');

                if (pos > 0) {

                    e.fields.put(
                        f[j].substring(0, pos).trim(),
                        f[j].substring(pos + 1).trim()
                    );
                }
            }

            node.events.add(e);
            p.events.add(e);

            if (e.type.equals("ARRANQUE")) {

                p.firstStartup = Math.min(
                    p.firstStartup,
                    time
                );
            }
        }
    }

    private void readHB(
            NodeData node,
            PartitionData p) throws IOException {

        Path file = pasta.resolve(
            "hb-p" + node.k
            + "-n" + node.i + ".txt"
        );

        if (!Files.exists(file)) {
            return;
        }

        node.hbExists = true;

        int number = 0;

        for (String line : lines(file)) {

            number++;

            if (line.isBlank()) {
                continue;
            }

            String[] f = line.trim().split(";", -1);

            // Formato COMINDIR05:
            // termo;hb;instante;ultimoSeq;ultimoTermo;n;bytes
            if (f.length != 7) {

                warn(
                    file.getFileName()
                    + ":" + number
                    + " — heartbeat sem 7 campos"
                );

                continue;
            }

            try {

                HB h = new HB(
                    node.k,
                    node.i,
                    Long.parseLong(f[0]),
                    Long.parseLong(f[1]),
                    Long.parseLong(f[2]),
                    Long.parseLong(f[3]),
                    Long.parseLong(f[4]),
                    Integer.parseInt(f[5]),
                    Integer.parseInt(f[6])
                );

                if (h.term < 0
                        || h.hb < 1
                        || h.time < 0
                        || h.lastSeq < 0
                        || h.lastTerm < 0
                        || h.count < 0
                        || h.bytes < 0) {

                    throw new IllegalArgumentException(
                        "Valores negativos"
                    );
                }

                node.heartbeats.add(h);
                p.heartbeats.add(h);

            } catch (RuntimeException e) {

                warn(
                    file.getFileName()
                    + ":" + number
                    + " — heartbeat inválido"
                );
            }
        }
    }

    private void readDiscarded() throws IOException {
        for (PartitionData p : particoes.values()) {
            for (NodeData node : p.nodes.values()) {
                Path file = pasta.resolve("descartados-p" + p.k + "-n" + node.i + ".txt");
                if (!Files.exists(file)) continue;
                int lineNumber = 0;
                for (String line : lines(file)) {
                    lineNumber++;
                    if (line.isBlank()) continue;
                    // auditId;seq;termo;sensor;temperatura;instante;L;V
                    String[] f = line.split(";", -1);
                    if (f.length != 8 || f[0].isBlank()) {
                        warn(file.getFileName() + ":" + lineNumber + " — descarte inválido");
                        continue;
                    }
                    try {
                        long seq = Long.parseLong(f[1]);
                        long term = Long.parseLong(f[2]);
                        if (seq < 1 || term < 0) throw new NumberFormatException();
                        new BigDecimal(f[4]);
                        Long.parseLong(f[6]);
                        if (f[5].isBlank() || f[7].isBlank())
                            throw new IllegalArgumentException("instante ou vetor vazio");
                        Discarded d = new Discarded(f[0], p.k, node.i,
                                seq, term, f[3], f[4], f[5]);
                        discardedByAudit.computeIfAbsent(f[0], x -> new ArrayList<>()).add(d);
                    } catch (RuntimeException ex) {
                        warn(file.getFileName() + ":" + lineNumber + " — descarte inválido");
                    }
                }
            }
        }
    }

    /** Líder final: o nó que liderou o maior termo conhecido. */
    private NodeData finalLeader(PartitionData p) {
        if (p.terms.isEmpty()) return null;
        for (TermData t : p.terms.descendingMap().values()) {
            if (t.leaders.size() == 1) {
                int id = t.leaders.iterator().next();
                NodeData node = p.nodes.get(id);
                if (node != null && node.logExists) return node;
            }
            if (t.leaders.size() > 1) return null;
        }
        return null;
    }

    private void readProducer() throws IOException {

        Path file = pasta.resolve("produtor.txt");

        if (!Files.exists(file)) {

            warn("produtor.txt não encontrado");
            return;
        }

        int number = 0;

        for (String line : lines(file)) {

            number++;

            if (line.isBlank()) {
                continue;
            }

            String[] f = line.trim().split(";", -1);

            // Formato:
            // instante;p<k>;sensor;temperatura
            if (f.length != 4) {

                warn(
                    "produtor.txt:" + number
                    + " — leitura inválida"
                );

                continue;
            }

            long time = numeric(f[0], -1);

            Matcher m = PART.matcher(f[1]);

            if (time < 0 || !m.matches()) {

                warn(
                    "produtor.txt:" + number
                    + " — instante ou partição inválidos"
                );

                continue;
            }

            int k = Integer.parseInt(m.group(1));

            if (k < 0 || k >= N) {

                warn(
                    "produtor.txt:" + number
                    + " — partição fora do intervalo"
                );

                continue;
            }

            try {
                new BigDecimal(f[3]);
            } catch (NumberFormatException e) {

                warn(
                    "produtor.txt:" + number
                    + " — temperatura inválida"
                );

                continue;
            }

            leituras.add(
                new Reading(
                    time,
                    k,
                    f[2],
                    f[3]
                )
            );
        }

        leituras.sort(
            Comparator.comparingLong(
                (Reading r) -> r.time
            )
        );
    }

    private void readMonitors() throws IOException {

        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(
                    pasta,
                    "monitor-*.txt"
                )) {

            for (Path file : stream) {

                for (String line : lines(file)) {

                    if (line.isBlank()) {
                        continue;
                    }

                    String[] f = line.split(";", -1);

                    if (f.length < 3) {
                        continue;
                    }

                    long time = numeric(f[0], -1);

                    Matcher m = PART.matcher(
                        f[2].trim()
                    );

                    if (time < 0 || !m.matches()) {
                        continue;
                    }

                    int k = Integer.parseInt(
                        m.group(1)
                    );

                    if (k < 0 || k >= N) {
                        continue;
                    }

                    monitores.add(
                        new MonitorEvent(
                            file.getFileName().toString(),
                            time,
                            f[1].trim(),
                            k
                        )
                    );
                }
            }
        }
    }

    private void readFailures(Path file)
            throws IOException {

        if (!Files.exists(file)) {

            warn(
                "Ficheiro de falhas não encontrado: "
                + file
            );

            return;
        }

        int number = 0;

        for (String line : lines(file)) {

            number++;

            if (line.isBlank()) {
                continue;
            }

            String[] f = line.trim().split(";", -1);

            if (f.length != 3) {

                warn(
                    file.getFileName()
                    + ":" + number
                    + " — falha inválida"
                );

                continue;
            }

            Matcher pm = PART.matcher(f[0]);
            Matcher nm = NODE.matcher(f[1]);

            if (!pm.matches() || !nm.matches()) {

                warn(
                    file.getFileName()
                    + ":" + number
                    + " — identificadores inválidos"
                );

                continue;
            }

            int k = Integer.parseInt(pm.group(1));
            int node = Integer.parseInt(nm.group(1));
            long time = numeric(f[2], -1);

            if (k < 0 || k >= N
                    || node < 1 || node > 3
                    || time < 0) {

                warn(
                    file.getFileName()
                    + ":" + number
                    + " — falha fora do intervalo"
                );

                continue;
            }

            falhas.add(
                new Failure(k, node, time)
            );
        }

        falhas.sort(
            Comparator.comparingLong(
                (Failure f) -> f.time
            )
        );
    }

    /* =====================================================
     * Reconstrução das eleições
     * ===================================================== */

    private void buildTerms() {

        for (PartitionData p : particoes.values()) {

            for (Event e : p.events) {

                long term = e.term();

                if (e.type.equals("TERMO")) {

                    String[] parts = e.raw.split(";", -1);

                    if (parts.length >= 3) {

                        Matcher m = TRANSITION.matcher(
                            parts[2].trim()
                        );

                        if (m.matches()) {

                            long newTerm = Long.parseLong(
                                m.group(2)
                            );

                            p.term(newTerm);
                        }
                    }

                    continue;
                }

                if (term < 0) {
                    continue;
                }

                TermData t = p.term(term);

                if (e.type.equals("CANDIDATO")) {

                    t.candidates.add(e.node);

                    t.firstCandidate = Math.min(
                        t.firstCandidate,
                        e.time
                    );

                    // O candidato vota em si próprio
                    // quando RaftState.tick() é executado.
                    t.addVote(e.node, e.node);
                }

                if (e.type.equals("VOTO")) {

                    String candidate = e.field(
                        "candidato"
                    );

                    int candidateNode =
                        parseNode(candidate);

                    if (candidateNode >= 1
                            && candidateNode <= 3
                            && e.raw.endsWith(";concedido")) {

                        t.addVote(
                            candidateNode,
                            e.node
                        );
                    }
                }

                if (e.type.equals("LIDER")) {

                    t.leaders.add(e.node);

                    t.firstLeader = Math.min(
                        t.firstLeader,
                        e.time
                    );

                    Matcher m = VOTE_LIST.matcher(
                        e.raw
                    );

                    if (m.find()) {

                        Set<Integer> voters =
                            new TreeSet<>();

                        for (String v :
                                m.group(1).split(",")) {

                            int voter = integer(
                                v.trim(), -1
                            );

                            if (voter >= 1 && voter <= 3) {
                                voters.add(voter);
                            }
                        }

                        t.leaderVotes.put(
                            e.node,
                            voters
                        );

                        for (int voter : voters) {
                            t.addVote(e.node, voter);
                        }
                    }
                }

                if (e.type.equals("LIDER_ATIVO")) {
                    t.leaders.add(e.node);
                }
            }

            // Heartbeats publicados são evidência
            // adicional de um líder ativo.
            for (HB h : p.heartbeats) {

                p.term(h.term)
                    .leaders.add(h.node);
            }
        }
    }

    private static int parseNode(String value) {

        if (value == null) {
            return -1;
        }

        Matcher m = NODE.matcher(value);

        if (m.matches()) {
            return integer(m.group(1), -1);
        }

        return integer(value, -1);
    }

    /* =====================================================
     * Relatório das eleições
     * ===================================================== */

    private void reportElections() {

        System.out.println();
        System.out.println(
            "========== ELEIÇÕES =========="
        );

        int unsafeTerms = 0;

        for (PartitionData p : particoes.values()) {

            System.out.println();
            System.out.println(
                "--- Partição p" + p.k + " ---"
            );

            if (p.terms.isEmpty()) {

                System.out.println(
                    "Sem eleições identificadas."
                );

                continue;
            }

            for (TermData t : p.terms.values()) {

                System.out.println(
                    "Termo " + t.term
                );

                System.out.println(
                    "  candidatos: "
                    + nodeList(t.candidates)
                );

                if (t.votes.isEmpty()) {

                    System.out.println(
                        "  votos: sem informação"
                    );

                } else {

                    for (Map.Entry<Integer, Set<Integer>> entry
                            : t.votes.entrySet()) {

                        System.out.println(
                            "  candidato n" + entry.getKey()
                            + ": " + entry.getValue().size()
                            + " votos identificados "
                            + nodeList(entry.getValue())
                        );
                    }
                }

                if (t.leaders.isEmpty()) {

                    System.out.println(
                        "  resultado: VOTO DIVIDIDO"
                        + " / sem líder identificado"
                    );

                } else {

                    System.out.println(
                        "  líder(es): "
                        + nodeList(t.leaders)
                    );
                }

                if (t.leaders.size() > 1) {
                    unsafeTerms++;
                }

                if (t.firstCandidate != Long.MAX_VALUE
                        && t.firstLeader != Long.MAX_VALUE
                        && t.firstLeader >= t.firstCandidate) {

                    System.out.println(
                        "  duração da eleição: "
                        + duration(
                            t.firstLeader
                            - t.firstCandidate
                        )
                    );

                } else {

                    System.out.println(
                        "  duração da eleição:"
                        + " não determinável"
                    );
                }
            }
        }

        System.out.println();
        System.out.println(
            "Termos com mais de um líder: "
            + unsafeTerms
        );

        System.out.println(
            unsafeTerms == 0
                ? "Segurança da eleição: OK"
                : "Segurança da eleição: VIOLAÇÃO"
        );
    }

    private static String nodeList(Set<Integer> nodes) {

        if (nodes.isEmpty()) {
            return "-";
        }

        List<String> result = new ArrayList<>();

        for (int node : nodes) {
            result.add("n" + node);
        }

        return String.join(", ", result);
    }

    /* =====================================================
     * Verificação dos termos persistidos
     * ===================================================== */

    private void reportTermMonotonicity() {

        System.out.println();
        System.out.println(
            "========== MONOTONIA DOS TERMOS =========="
        );

        int violations = 0;

        for (PartitionData p : particoes.values()) {

            for (NodeData node : p.nodes.values()) {

                long previous = -1;
                boolean valid = true;
                boolean observed = false;

                // A ordem do ficheiro de eventos
                // representa a ordem local.
                for (Event e : node.events) {

                    long term = e.term();

                    if (e.type.equals("TERMO")) {

                        String[] f = e.raw.split(";", -1);

                        if (f.length >= 3) {

                            Matcher m = TRANSITION.matcher(
                                f[2].trim()
                            );

                            if (m.matches()) {

                                long oldTerm = Long.parseLong(
                                    m.group(1)
                                );

                                long newTerm = Long.parseLong(
                                    m.group(2)
                                );

                                if (newTerm < oldTerm
                                        || (previous >= 0
                                            && oldTerm < previous)) {
                                    valid = false;
                                }

                                term = newTerm;
                            }
                        }
                    }

                    if (term < 0) {
                        continue;
                    }

                    observed = true;

                    if (previous >= 0 && term < previous) {
                        valid = false;
                    }

                    previous = Math.max(
                        previous, term
                    );
                }

                // O estado persistido nunca deve
                // ser inferior ao último termo observado.
                if (node.stateExists
                        && previous >= 0
                        && node.persistedTerm < previous) {

                    valid = false;
                }

                if (!valid) {
                    violations++;
                }

                System.out.println(
                    "p" + p.k + "/n" + node.i
                    + ": "
                    + (valid ? "OK" : "VIOLAÇÃO")
                    + " | último observado="
                    + (observed ? previous : "-")
                    + " | persistido="
                    + (node.stateExists
                        ? node.persistedTerm
                        : "-")
                );
            }
        }

        System.out.println(
            "Nós com diminuição identificada: "
            + violations
        );
    }

    /* =====================================================
     * Tempo até ao primeiro líder
     * ===================================================== */

    private void reportFirstLeader() {

        System.out.println();
        System.out.println(
            "========== PRIMEIRO LÍDER =========="
        );

        for (PartitionData p : particoes.values()) {

            long firstLeader = Long.MAX_VALUE;
            int firstNode = 0;

            for (Event e : p.events) {

                if (e.type.equals("LIDER")
                        && e.time < firstLeader) {

                    firstLeader = e.time;
                    firstNode = e.node;
                }
            }

            System.out.println(
                "Partição p" + p.k
            );

            if (p.firstStartup == Long.MAX_VALUE) {

                System.out.println(
                    "  Primeiro arranque: desconhecido"
                );

            } else {

                System.out.println(
                    "  Primeiro arranque: "
                    + p.firstStartup
                );
            }

            if (firstLeader == Long.MAX_VALUE) {

                System.out.println(
                    "  Primeiro líder: não identificado"
                );

                continue;
            }

            System.out.println(
                "  Primeiro líder: n" + firstNode
                + " em " + firstLeader
            );

            if (p.firstStartup != Long.MAX_VALUE
                    && firstLeader >= p.firstStartup) {

                System.out.println(
                    "  Tempo até ao primeiro líder: "
                    + duration(
                        firstLeader - p.firstStartup
                    )
                );

            } else {

                System.out.println(
                    "  Tempo: não determinável"
                );
            }
        }
    }

    /* =====================================================
     * Falhas e indisponibilidade
     * ===================================================== */

    private void reportFailures() {

        if (falhas.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println(
            "========== FALHAS E INDISPONIBILIDADE =========="
        );

        for (Failure failure : falhas) {

            PartitionData p =
                particoes.get(failure.k);

            /*
             * Determinar o termo do nó afetado
             * a partir do seu último heartbeat
             * anterior à falha.
             */
            long failedTerm = -1;

            for (HB h : p.heartbeats) {

                if (h.node == failure.node
                        && h.time <= failure.time) {

                    failedTerm = Math.max(
                        failedTerm, h.term
                    );
                }
            }

            /*
             * Se o nó falhado não tinha heartbeat,
             * o termo de referência não é conhecido
             * diretamente a partir do ficheiro HB.
             */
            if (failedTerm < 0) {

                System.out.println(
                    "p" + failure.k
                    + "/n" + failure.node
                    + " | falha=" + failure.time
                    + " | indisponibilidade:"
                    + " termo do nó falhado desconhecido"
                );

                continue;
            }

            HB recovery = null;

            for (HB h : p.heartbeats) {

                if (h.time > failure.time
                        && h.term > failedTerm) {

                    if (recovery == null
                            || h.time < recovery.time) {

                        recovery = h;
                    }
                }
            }

            System.out.println(
                "Falha p" + failure.k
                + "/n" + failure.node
                + " em " + failure.time
                + " | termo anterior=" + failedTerm
            );

            if (recovery == null) {

                System.out.println(
                    "  Novo líder: não observado"
                );

                System.out.println(
                    "  Indisponibilidade:"
                    + " sem limite final observado"
                );

                continue;
            }

            System.out.println(
                "  Primeiro LOTE de termo superior:"
                + " n" + recovery.node
                + ", termo=" + recovery.term
                + ", hb=" + recovery.hb
                + ", instante=" + recovery.time
            );

            System.out.println(
                "  Indisponibilidade: "
                + duration(
                    recovery.time - failure.time
                )
            );

            int count = 0;

            for (Reading r : leituras) {

                if (r.k == failure.k
                        && r.time >= failure.time
                        && r.time < recovery.time) {

                    count++;
                }
            }

            System.out.println(
                "  Leituras enviadas no intervalo: "
                + count
            );

            for (Reading r : leituras) {

                if (r.k == failure.k
                        && r.time >= failure.time
                        && r.time < recovery.time) {

                    System.out.println(
                        "    " + r.time
                        + ";" + r.sensor
                        + ";" + r.temperature
                    );
                }
            }
        }
    }

    /* =====================================================
     * Leituras perdidas
     * ===================================================== */

    private void reportLostReadings() {

        System.out.println();
        System.out.println(
            "========== LEITURAS PERDIDAS =========="
        );

        int totalLost = 0;

        for (PartitionData p : particoes.values()) {

            /*
             * Para cada leitura, procurar uma ocorrência
             * em qualquer um dos logs finais.
             *
             * Usa-se a maior multiplicidade encontrada
             * num único nó, e não a soma dos nós:
             * as réplicas guardam cópias dos mesmos dados.
             */
            Map<String, Integer> available =
                new HashMap<>();

            for (NodeData node : p.nodes.values()) {

                Map<String, Integer> counts =
                    new HashMap<>();

                for (Record r : node.records.values()) {

                    if (r.sensor.startsWith("ALARM-")) {
                        continue;
                    }

                    counts.merge(
                        r.key(), 1, Integer::sum
                    );
                }

                for (Map.Entry<String, Integer> entry
                        : counts.entrySet()) {

                    available.merge(
                        entry.getKey(),
                        entry.getValue(),
                        Math::max
                    );
                }
            }

            List<Reading> missing =
                new ArrayList<>();

            for (Reading r : leituras) {

                if (r.k != p.k) {
                    continue;
                }

                int count = available.getOrDefault(
                    r.key(), 0
                );

                if (count > 0) {

                    available.put(
                        r.key(),
                        count - 1
                    );

                } else {

                    missing.add(r);
                }
            }

            totalLost += missing.size();

            System.out.println(
                "Partição p" + p.k
                + ": " + missing.size()
                + " leituras sem correspondência"
            );

            for (Reading r : missing) {

                System.out.println(
                    "  PERDIDA;"
                    + r.time + ";"
                    + r.sensor + ";"
                    + r.temperature
                );
            }
        }

        System.out.println(
            "Total de leituras sem correspondência: "
            + totalLost
        );

        System.out.println(
            "Critério: sensor + temperatura,"
            + " respeitando multiplicidades."
        );

        System.out.println(
            "A correspondência é por conteúdo:"
            + " não identifica univocamente leituras"
            + " iguais enviadas em momentos diferentes."
        );
    }

    /* =====================================================
     * Eventos TRUNCADO
     * ===================================================== */

    private void reportTruncations() {
        System.out.println();
        System.out.println("========== TRUNCAMENTOS ==========");
        int events = 0;
        long discardedTotal = 0;
        long recoveredWithNewSeq = 0;
        int unverified = 0;

        for (PartitionData p : particoes.values()) {
            NodeData leader = finalLeader(p);
            for (Event e : p.events) {
                if (!e.type.equals("TRUNCADO")) continue;
                events++;
                long from = e.longField("de", -1);
                long discarded = e.longField("descartados", -1);
                String terms = e.field("termos");
                String auditId = e.field("auditoria");
                if (discarded >= 0) discardedTotal += discarded;
                System.out.println("p" + p.k + "/n" + e.node
                        + " | instante=" + e.time + " | de=" + from
                        + " | descartados=" + discarded
                        + " | termos=" + (terms == null ? "-" : terms));
                if (auditId == null || leader == null) {
                    unverified++;
                    System.out.println("  Reaparecidos com outro seq: sem auditoria ou líder final inequívoco");
                    continue;
                }
                List<Discarded> archive = discardedByAudit.get(auditId);
                // Um truncamento sem registos descartados não precisa de entradas no arquivo.
                if (discarded == 0 && archive == null) {
                    archive = Collections.emptyList();
                }
                if (archive == null || discarded < 0 || archive.size() != discarded
                        || archive.stream().anyMatch(d -> d.k != p.k || d.node != e.node)) {
                    unverified++;
                    System.out.println("  Reaparecidos com outro seq: arquivo ausente/incompleto");
                    continue;
                }
                // Identidade aproximada da leitura: sensor + temperatura normalizada.
                // O instante não é estável entre líderes e, por isso, não faz parte da chave.
                // O multiconjunto evita reutilizar o mesmo registo final nesta operação.
                Map<String, Map<Long, Integer>> finalRecords = new HashMap<>();
                for (Record r : leader.records.values()) {
                    String identity = readingKey(r.sensor, r.temperature);
                    finalRecords.computeIfAbsent(identity, x -> new TreeMap<>())
                            .merge(r.seq, 1, Integer::sum);
                }
                // Primeiro preservar as coincidências na sequência original.
                // Só os descartes sem correspondência na posição original
                // podem contar como reaparecidos com um seq diferente.
                List<Discarded> unmatched = new ArrayList<>();
                for (Discarded d : archive) {
                    Map<Long, Integer> positions = finalRecords.get(d.identity());
                    int sameSeqCount = positions == null ? 0 : positions.getOrDefault(d.oldSeq, 0);
                    if (sameSeqCount > 0) {
                        positions.put(d.oldSeq, sameSeqCount - 1);
                    } else {
                        unmatched.add(d);
                    }
                }
                int reappeared = 0;
                for (Discarded d : unmatched) {
                    Map<Long, Integer> positions = finalRecords.get(d.identity());
                    if (positions == null) continue;
                    for (Map.Entry<Long, Integer> entry : positions.entrySet()) {
                        if (entry.getKey() != d.oldSeq && entry.getValue() > 0) {
                            reappeared++;
                            entry.setValue(entry.getValue() - 1);
                            break;
                        }
                    }
                }
                recoveredWithNewSeq += reappeared;
                System.out.println("  Reaparecidos com outro seq no líder final n"
                        + leader.i + ": " + reappeared + "/" + discarded
                        + " (correspondência por sensor e temperatura; instante ignorado)");
            }
        }
        System.out.println("Eventos TRUNCADO: " + events);
        System.out.println("Registos descartados: " + discardedTotal);
        System.out.println("Reaparecidos com outro seq (eventos verificáveis): "
                + recoveredWithNewSeq);
        System.out.println("Eventos sem auditoria suficiente: " + unverified);
    }

    /* =====================================================
     * Coincidência final dos logs
     * ===================================================== */

    private void reportFinalAgreement() {

        System.out.println();
        System.out.println(
            "========== COINCIDÊNCIA FINAL =========="
        );

        for (PartitionData p : particoes.values()) {

            System.out.println();
            System.out.println(
                "--- Partição p" + p.k + " ---"
            );

            long minSeq = Long.MAX_VALUE;
            boolean complete = true;

            for (NodeData node : p.nodes.values()) {

                if (!node.logExists) {
                    complete = false;
                }

                minSeq = Math.min(
                    minSeq,
                    node.lastSeq()
                );

                System.out.println(
                    "n" + node.i
                    + ": ultimoSeq=" + node.lastSeq()
                    + ", ultimoTermo=" + node.lastTerm()
                    + (node.logExists
                        ? ""
                        : " (ficheiro ausente)")
                );
            }

            if (!complete) {

                System.out.println(
                    "Coincidência: INDETERMINADA"
                    + " (faltam ficheiros de nós)"
                );

                continue;
            }

            if (minSeq == Long.MAX_VALUE) {
                minSeq = 0;
            }

            List<Long> different =
                new ArrayList<>();

            for (long seq = 1; seq <= minSeq; seq++) {

                Record reference = null;
                boolean mismatch = false;

                for (NodeData node : p.nodes.values()) {

                    Record current =
                        node.records.get(seq);

                    if (current == null) {

                        mismatch = true;
                        break;
                    }

                    if (reference == null) {

                        reference = current;

                    } else if (!reference.line.equals(
                            current.line)) {

                        mismatch = true;
                        break;
                    }
                }

                if (mismatch) {
                    different.add(seq);
                }
            }

            System.out.println(
                "Intervalo comparado: 1.."
                + minSeq
            );

            if (different.isEmpty()) {

                System.out.println(
                    "Coincidência final: IGUAL"
                    + " no prefixo comum"
                );

            } else {

                System.out.println(
                    "Coincidência final: DIVERGENTE"
                );

                System.out.println(
                    "Seq diferentes: "
                    + different
                );
            }

            if (minSeq == 0) {

                System.out.println(
                    "Nota: prefixo comum vazio."
                );
            }

            long maxSeq = 0;

            for (NodeData node : p.nodes.values()) {
                maxSeq = Math.max(
                    maxSeq,
                    node.lastSeq()
                );
            }

            if (maxSeq > minSeq) {

                System.out.println(
                    "Existem sufixos adicionais fora"
                    + " do intervalo comparado."
                );
            }
        }
    }

    /* =====================================================
     * Estatísticas adicionais
     * ===================================================== */

    private void reportSummary() {

        System.out.println();
        System.out.println(
            "========== RESUMO GERAL =========="
        );

        System.out.println(
            "Partições: " + N
        );

        System.out.println(
            "Nós esperados: " + (N * 3)
        );

        int logs = 0;
        int states = 0;
        int eventFiles = 0;
        int hbFiles = 0;

        long records = 0;

        for (PartitionData p : particoes.values()) {

            for (NodeData node : p.nodes.values()) {

                if (node.logExists) {
                    logs++;
                }

                if (node.stateExists) {
                    states++;
                }

                if (node.eventsExist) {
                    eventFiles++;
                }

                if (node.hbExists) {
                    hbFiles++;
                }

                records += node.records.size();
            }
        }

        System.out.println(
            "Ficheiros no-* encontrados: " + logs
        );

        System.out.println(
            "Ficheiros estado-* encontrados: " + states
        );

        System.out.println(
            "Ficheiros eventos-* encontrados: " + eventFiles
        );

        System.out.println(
            "Ficheiros hb-* encontrados: " + hbFiles
        );

        System.out.println(
            "Registos armazenados nos nós: "
            + records
        );

        System.out.println(
            "Leituras do produtor: "
            + leituras.size()
        );

        System.out.println(
            "Falhas anotadas: "
            + falhas.size()
        );

        System.out.println(
            "Eventos monitor-* lidos: "
            + monitores.size()
        );
    }

    private void reportWarnings() {

        if (avisos.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println(
            "========== AVISOS DE LEITURA =========="
        );

        for (String warning : avisos) {
            System.out.println(
                "AVISO: " + warning
            );
        }
    }

    /* =====================================================
     * Geração do relatório
     * ===================================================== */

    private void report() {

        System.out.println(
            "=========================================="
        );

        System.out.println(
            "RELATÓRIO RAFT04 — COMINDIR05"
        );

        System.out.println(
            "=========================================="
        );

        System.out.println(
            "Pasta: " + pasta.toAbsolutePath()
        );

        reportSummary();
        reportElections();
        reportTermMonotonicity();
        reportFirstLeader();
        reportFailures();
        reportLostReadings();
        reportTruncations();
        reportFinalAgreement();
        reportWarnings();

        System.out.println();
        System.out.println(
            "========== FIM DO RELATÓRIO =========="
        );
    }

    /* =====================================================
     * Main
     * ===================================================== */

    public static void main(String[] args) {

        if (args.length < 2 || args.length > 3) {

            System.out.println(
                "Uso: RaftCheck <N> <pasta> [falhas.txt]"
            );

            return;
        }

        int n;

        try {

            n = Integer.parseInt(args[0]);

            if (n < 1) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {

            System.out.println(
                "N inválido: " + args[0]
            );

            return;
        }

        Path pasta = Paths.get(args[1]);

        if (!Files.isDirectory(pasta)) {

            System.out.println(
                "Pasta inválida: " + pasta
            );

            return;
        }

        Path failures = null;

        if (args.length == 3) {

            failures = Paths.get(args[2]);

            // Aceitar caminhos relativos à pasta analisada.
            if (!failures.isAbsolute()
                    && !Files.exists(failures)) {

                failures = pasta.resolve(
                    failures
                );
            }
        }

        try {

            RaftCheck check =
                new RaftCheck(n, pasta);

            check.readAll(failures);

            check.report();

        } catch (IOException e) {

            System.err.println(
                "Erro de leitura: "
                + e.getMessage()
            );
        }
    }
}
