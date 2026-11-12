package raft04;

import java.util.function.Consumer;

/**
 * Detetor de falhas do líder por partição, usado pelo PRODUTOR e pelo
 * PROCESSADOR (COMINDIR05). Acompanha o LÍDER ELEITO, não um processo fixo:
 *
 * - onHeartbeat(termo, lider, hb) ignora termos inferiores ao maior já
 *   observado (líder obsoleto);
 * - assinala NOVO_LIDER quando o termo aumenta (inclui o primeiro líder, de
 *   termo 0 -> 1);
 * - assinala HB_PERDIDO quando há um salto de hb dentro do MESMO termo;
 * - assinala SUSPEITA após TIMEOUT = FATOR × Δ sem heartbeats válidos, e
 *   RETOMA no primeiro heartbeat depois de uma suspeita.
 */
public final class HeartbeatMonitor {
    public enum State {
        DESCONHECIDO, ATIVO, SUSPEITO
    }

    private final int partition;
    private final long timeoutNanos;
    private final Consumer<String> log;

    private State state = State.DESCONHECIDO;
    private long lastNanos; // instante local da última receção
    private long term = 0; // maior termo observado
    private long hb = 0; // último heartbeat observado nesse termo
    private int leader = 0;

    public HeartbeatMonitor(int partition, long periodMs, int factor, Consumer<String> log) {
        this.partition = partition;
        this.timeoutNanos = periodMs * factor * 1_000_000L;
        this.log = log;
        this.lastNanos = System.nanoTime();
    }

    // thread de receção: chegou um LOTE da partição
    public synchronized void onHeartbeat(long term, int leader, long hb) {
        if (term < this.term)
            return; // líder obsoleto
        if (term == this.term && hb <= this.hb)
            return; // duplicado ou atrasado
        if (term > this.term) {
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + this.term + "->" + term + ";lider=n" + leader);
            this.term = term;
            this.hb = 0; // o hb numera-se de 1 dentro de cada liderança
        } else if (hb != this.hb + 1) {
            log.accept("HB_PERDIDO;p" + partition + ";esperado=" + (this.hb + 1) + ";recebido=" + hb);
        }
        if (state == State.SUSPEITO) {
            log.accept("RETOMA;p" + partition + ";termo=" + term + ";hb=" + hb);
        }
        this.leader = leader;
        this.hb = hb;
        this.lastNanos = System.nanoTime();
        this.state = State.ATIVO;
    }

    // thread de verificação: chamada a cada VERIFICACAO_MS
    public synchronized void check() {
        long elapsed = System.nanoTime() - lastNanos;
        if (state != State.SUSPEITO && elapsed > timeoutNanos) {
            state = State.SUSPEITO;
            log.accept("SUSPEITA;p" + partition + ";silencio_ms=" + elapsed / 1_000_000);
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long term() {
        return term;
    }

    public synchronized int leader() {
        return leader;
    }
}
