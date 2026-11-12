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

/**
 * Estado RAFT de um nó de partição: termo, voto, papel e prazo de eleição.
 *
 * Este é o código dado na ficha COMINDIR05. As quatro propriedades
 * "raft04.falha*" implementam as falhas deliberadas do ensaio 10 e são
 * FALSO por omissão: sem elas, o comportamento é exatamente o da ficha.
 *
 * -Draft04.falhaMaioria        maioria calculada como votes.size() >= G/2
 * -Draft04.falhaPersistencia   termo e votedFor só em memória
 * -Draft04.falhaUpToDate       voto concedido sem a verificação upToDate
 * -Draft04.falhaTermoInferior  seguidor aplica lotes de termo inferior ao seu
 */
public final class RaftState {
    public enum Role { SEGUIDOR, CANDIDATO, LIDER }

    // resposta a um PEDIDO_VOTO: termo em que é dada e se o voto foi concedido
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

    // falhas deliberadas do ensaio 10 (FALSO por omissão = código da ficha)
    private static final boolean FALHA_MAIORIA = Boolean.getBoolean("raft04.falhaMaioria");
    private static final boolean FALHA_PERSISTENCIA = Boolean.getBoolean("raft04.falhaPersistencia");
    private static final boolean FALHA_UPTODATE = Boolean.getBoolean("raft04.falhaUpToDate");
    private static final boolean FALHA_TERMO_INFERIOR = Boolean.getBoolean("raft04.falhaTermoInferior");

    // estado persistente: gravado antes de qualquer mensagem que dele dependa
    private long term = 0;
    private int votedFor = 0;              // 0 = ainda não votou neste termo

    // estado volátil
    private Role role = Role.SEGUIDOR;
    private int leader = 0;                // 0 = desconhecido
    private final Set<Integer> votes = new HashSet<>();
    private long deadline;                 // System.nanoTime() a partir do qual há eleição

    public RaftState(int partition, int self, int groupSize, long minMs, long maxMs,
                     Path stateFile, Consumer<String> log) throws IOException {
        this.partition = partition;
        this.self = self;
        this.groupSize = groupSize;
        this.minNanos = minMs * 1_000_000L;
        this.maxNanos = maxMs * 1_000_000L;
        this.stateFile = stateFile;
        this.log = log;
        if (!FALHA_PERSISTENCIA && Files.exists(stateFile)) {
            String[] f = Files.readString(stateFile, StandardCharsets.UTF_8).trim().split(";");
            term = Long.parseLong(f[0]);
            votedFor = Integer.parseInt(f[1]);
        }
        resetTimer();
    }

    private void resetTimer() {
        long t = (maxNanos > minNanos)
                ? ThreadLocalRandom.current().nextLong(minNanos, maxNanos + 1)
                : minNanos;
        deadline = System.nanoTime() + t;
    }

    private void persist() {
        if (FALHA_PERSISTENCIA) {
            return; // ensaio 10: termo e votedFor mantidos só em memória
        }
        try {
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, term + ";" + votedFor + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, stateFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // termo superior observado: adota-o e passa a seguidor
    private void adoptTerm(long newTerm) {
        if (role == Role.LIDER) log.accept("DEMISSAO;p" + partition + ";termo=" + term);
        log.accept("TERMO;p" + partition + ";" + term + "->" + newTerm);
        term = newTerm;
        votedFor = 0;
        role = Role.SEGUIDOR;
        leader = 0;
        persist();
    }

    // thread de verificação, a cada VERIFICACAO_MS: devolve o termo da candidatura ou -1
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
        if (FALHA_MAIORIA && votes.size() >= groupSize / 2) {
            // ensaio 10: maioria calculada como votes.size() >= G/2 — o auto-voto
            // sozinho "chega à maioria" e o candidato promove-se a líder sem
            // qualquer voto dos outros nós (com G = 3, um voto em cada termo é
            // eleito: podem existir vários líderes no mesmo termo)
            role = Role.LIDER;
            leader = self;
            log.accept("LIDER;p" + partition + ";termo=" + term + ";votos=" + votes);
        }
        return term;
    }

    // PEDIDO_VOTO de outro nó
    public synchronized Vote onVoteRequest(long cTerm, int candidate, long cLastSeq, long cLastTerm,
                                           long myLastSeq, long myLastTerm) {
        if (cTerm > term) adoptTerm(cTerm);
        boolean upToDate = FALHA_UPTODATE || cLastTerm > myLastTerm
                || (cLastTerm == myLastTerm && cLastSeq >= myLastSeq);
        boolean granted = cTerm == term
                && (votedFor == 0 || votedFor == candidate)
                && upToDate;
        if (granted) {
            votedFor = candidate;
            persist();
            resetTimer();
        }
        log.accept("VOTO;p" + partition + ";termo=" + term + ";candidato=n" + candidate
                + ";" + (granted ? "concedido" : "recusado"));
        return new Vote(term, granted);
    }

    // VOTO recebido: devolve true apenas no instante em que este nó se torna líder
    public synchronized boolean onVote(long vTerm, int voter, int candidate, boolean granted) {
        if (vTerm > term) { adoptTerm(vTerm); return false; }
        if (role != Role.CANDIDATO || vTerm != term || candidate != self || !granted) return false;
        votes.add(voter);
        // maioria: G/2 (divisão inteira) + 1. Com G = 3: 2 votos.
        // ensaio 10: a falha calcula a maioria como votes.size() >= G/2 (1 voto).
        if (FALHA_MAIORIA ? votes.size() < groupSize / 2 : votes.size() <= groupSize / 2) return false;
        role = Role.LIDER;
        leader = self;
        log.accept("LIDER;p" + partition + ";termo=" + term + ";votos=" + votes);
        return true;
    }

    // LOTE de outro nó: devolve true se o lote deve ser aplicado
    public synchronized boolean onBatch(long bTerm, int bLeader) {
        if (bTerm < term) {
            // líder obsoleto. ensaio 10: com a falha, o seguidor aplica-o na mesma.
            return FALHA_TERMO_INFERIOR;
        }
        if (bTerm > term) adoptTerm(bTerm);
        if (role == Role.LIDER) {                          // nunca deveria acontecer
            log.accept("ERRO;p" + partition + ";dois lideres no termo " + term);
            return false;
        }
        role = Role.SEGUIDOR;                              // um candidato reconhece o líder
        if (leader != bLeader) {
            leader = bLeader;
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + term + ";lider=n" + bLeader);
        }
        resetTimer();
        return true;
    }

    public synchronized long termIfLeader() { return role == Role.LIDER ? term : -1; }
    public synchronized long term()         { return term; }
    public synchronized Role role()         { return role; }
    public synchronized int leader()        { return leader; }
}
