package raft04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class RaftState {
    public enum Role { SEGUIDOR, CANDIDATO, LIDER }

    public static final class Vote {
        public final long term;
        public final boolean granted;
        Vote(long term, boolean granted) { this.term = term; this.granted = granted; }
    }

    private final int partition;
    private final int self;
    private final int groupSize;
    private final long minNanos;
    private final long maxNanos;
    private final Path stateFile;
    private final Consumer<String> log;
    private long term;
    private int votedFor;
    private Role role = Role.SEGUIDOR;
    private int leader;
    private final Set<Integer> votes = new HashSet<>();
    private long deadline;

    public RaftState(int partition, int self, int groupSize, long minMs, long maxMs,
            Path stateFile, Consumer<String> log) throws IOException {
        if (groupSize < 1 || self < 1 || self > groupSize || minMs <= 0 || maxMs < minMs) {
            throw new IllegalArgumentException("Parâmetros de eleição inválidos");
        }
        this.partition = partition;
        this.self = self;
        this.groupSize = groupSize;
        this.minNanos = Math.multiplyExact(minMs, 1_000_000L);
        this.maxNanos = Math.multiplyExact(maxMs, 1_000_000L);
        this.stateFile = stateFile;
        this.log = log;
        if (Files.exists(stateFile)) {
            String[] fields = Files.readString(stateFile, StandardCharsets.UTF_8).trim().split(";", -1);
            if (fields.length != 2) throw new IOException("Estado RAFT inválido: " + stateFile);
            term = Long.parseLong(fields[0]);
            votedFor = Integer.parseInt(fields[1]);
            if (term < 0 || votedFor < 0 || votedFor > groupSize) {
                throw new IOException("Estado RAFT fora dos limites: " + stateFile);
            }
        }
        resetTimer();
    }

    private void resetTimer() {
        long timeout = maxNanos > minNanos
                ? ThreadLocalRandom.current().nextLong(minNanos, maxNanos + 1)
                : minNanos;
        deadline = System.nanoTime() + timeout;
    }

    private void persist() {
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, term + ";" + votedFor + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível persistir o estado RAFT em " + stateFile, e);
        }
    }

    private void adoptTerm(long newTerm) {
        if (role == Role.LIDER) log.accept("DEMISSAO;p" + partition + ";termo=" + term);
        log.accept("TERMO;p" + partition + ";" + term + "->" + newTerm);
        term = newTerm;
        votedFor = 0;
        role = Role.SEGUIDOR;
        leader = 0;
        votes.clear();
        persist();
        resetTimer();
    }

    public synchronized long tick() {
        if (role == Role.LIDER || System.nanoTime() < deadline) return -1;
        term++;
        votedFor = self;
        persist();
        role = Role.CANDIDATO;
        leader = 0;
        votes.clear();
        votes.add(self);
        resetTimer();
        log.accept("CANDIDATO;p" + partition + ";termo=" + term);
        return term;
    }

    public synchronized Vote onVoteRequest(long candidateTerm, int candidate, long candidateLastSeq,
            long candidateLastTerm, long myLastSeq, long myLastTerm) {
        if (candidate < 1 || candidate > groupSize || candidateLastSeq < 0 || candidateLastTerm < 0) {
            log.accept("VOTO;p" + partition + ";termo=" + term + ";candidato=n" + candidate + ";recusado");
            return new Vote(term, false);
        }
        if (candidateTerm > term) adoptTerm(candidateTerm);
        boolean upToDate = candidateLastTerm > myLastTerm
                || (candidateLastTerm == myLastTerm && candidateLastSeq >= myLastSeq);
        boolean granted = candidateTerm == term && (votedFor == 0 || votedFor == candidate) && upToDate;
        if (granted) {
            votedFor = candidate;
            persist();
            resetTimer();
        }
        log.accept("VOTO;p" + partition + ";termo=" + term + ";candidato=n" + candidate
                + ";" + (granted ? "concedido" : "recusado"));
        return new Vote(term, granted);
    }

    public synchronized boolean onVote(long voteTerm, int voter, int candidate, boolean granted) {
        if (voteTerm > term) {
            adoptTerm(voteTerm);
            return false;
        }
        if (role != Role.CANDIDATO || voteTerm != term || candidate != self || !granted
                || voter < 1 || voter > groupSize) return false;
        votes.add(voter);
        if (votes.size() <= groupSize / 2) return false;
        role = Role.LIDER;
        leader = self;
        log.accept("LIDER;p" + partition + ";termo=" + term + ";votos=" + votes);
        return true;
    }

    public synchronized boolean onBatch(long batchTerm, int batchLeader) {
        if (batchLeader < 1 || batchLeader > groupSize || batchTerm < 0) return false;
        if (batchTerm < term) return false;
        if (batchTerm > term) adoptTerm(batchTerm);
        if (role == Role.LIDER) {
            log.accept("ERRO;p" + partition + ";dois lideres no termo " + term);
            return false;
        }
        role = Role.SEGUIDOR;
        if (leader != batchLeader) {
            leader = batchLeader;
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + term + ";lider=n" + batchLeader);
        }
        resetTimer();
        return true;
    }

    public synchronized long termIfLeader() { return role == Role.LIDER ? term : -1; }
    public synchronized long term() { return term; }
    public synchronized Role role() { return role; }
    public synchronized int leader() { return leader; }
}
