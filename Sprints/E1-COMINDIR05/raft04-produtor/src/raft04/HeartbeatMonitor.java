package raft04;

import java.util.function.Consumer;

public final class HeartbeatMonitor {
    public enum State { DESCONHECIDO, ATIVO, SUSPEITO }

    private final int partition;
    private final long timeoutNanos;
    private final Consumer<String> log;
    private State state = State.DESCONHECIDO;
    private long lastNanos = System.nanoTime();
    private long term = -1;
    private int leader;
    private long hb;

    public HeartbeatMonitor(int partition, long periodMs, int factor, Consumer<String> log) {
        this.partition = partition;
        this.timeoutNanos = periodMs * factor * 1_000_000L;
        this.log = log;
    }

    public synchronized void onHeartbeat(long newTerm, int newLeader, long newHb) {
        if (newTerm < term || (newTerm == term && newLeader != leader && leader != 0)) return;
        if (newTerm == term && newHb <= hb) return;
        if (newTerm > term) {
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + newTerm + ";lider=n" + newLeader);
        } else if (term >= 0 && newHb > hb + 1) {
            log.accept("HB_PERDIDO;p" + partition + ";termo=" + term + ";esperado=" + (hb + 1)
                    + ";recebido=" + newHb);
        }
        if (state == State.SUSPEITO) {
            log.accept("RETOMA;p" + partition + ";termo=" + newTerm + ";lider=n" + newLeader + ";hb=" + newHb);
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
            log.accept("SUSPEITA;p" + partition + ";silencio_ms=" + elapsed / 1_000_000);
        }
    }

    public synchronized State state() { return state; }
    public synchronized long term() { return term; }
    public synchronized int leader() { return leader; }
}
