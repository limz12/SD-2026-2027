package raft04;

import java.util.function.Consumer;

/** Monitor de heartbeats identificados por termo, líder e número de heartbeat. */
public final class HeartbeatMonitor {
    public enum State { DESCONHECIDO, ATIVO, SUSPEITO }

    private final int partition;
    private final long timeoutNanos;
    private final Consumer<String> log;
    private State state = State.DESCONHECIDO;
    private long lastNanos = System.nanoTime();
    private long term = -1;
    private int leader = 0;
    private long hb = 0;

    public HeartbeatMonitor(int partition, long periodMs, int factor, Consumer<String> log) {
        if (partition < 0 || periodMs <= 0 || factor <= 0 || log == null) {
            throw new IllegalArgumentException("Parâmetros inválidos do monitor");
        }
        this.partition = partition;
        this.timeoutNanos = Math.multiplyExact(
                Math.multiplyExact(periodMs, (long) factor), 1_000_000L);
        this.log = log;
    }

    public synchronized void onHeartbeat(long newTerm, int newLeader, long newHb) {
        if (newTerm < 0 || newLeader < 1 || newLeader > Wire.GRUPO || newHb < 1) {
            throw new IllegalArgumentException("Termo, líder ou heartbeat inválido");
        }
        if (newTerm < term) return;
        boolean changedTerm = newTerm > term;
        if (changedTerm) {
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + term + "->" + newTerm
                    + ";lider=n" + newLeader);
        } else {
            if (newLeader != leader) {
                log.accept("LIDER_CONFLITANTE;p" + partition + ";termo=" + newTerm
                        + ";lider=n" + leader + ";recebido=n" + newLeader);
                return;
            }
            if (newHb <= hb) return;
            if (newHb > hb + 1) {
                log.accept("HB_PERDIDO;p" + partition + ";termo=" + newTerm
                        + ";lider=n" + newLeader + ";esperado=" + (hb + 1)
                        + ";recebido=" + newHb);
            }
        }
        if (state == State.SUSPEITO) {
            log.accept("RETOMA;p" + partition + ";termo=" + newTerm
                    + ";lider=n" + newLeader + ";hb=" + newHb);
        }
        term = newTerm;
        leader = newLeader;
        hb = newHb;
        lastNanos = System.nanoTime();
        state = State.ATIVO;
    }

    public synchronized void check() {
        long elapsed = System.nanoTime() - lastNanos;
        if (state != State.SUSPEITO && elapsed > timeoutNanos) {
            state = State.SUSPEITO;
            log.accept("SUSPEITA;p" + partition + ";termo=" + term
                    + ";lider=" + (leader == 0 ? "desconhecido" : "n" + leader)
                    + ";silencio_ms=" + elapsed / 1_000_000L);
        }
    }

    public synchronized State state() { return state; }
    public synchronized long term() { return term; }
    public synchronized int leader() { return leader; }
    public synchronized long hb() { return hb; }
}
