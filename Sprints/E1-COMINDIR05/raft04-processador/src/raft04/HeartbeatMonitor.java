package raft04;

import java.util.function.Consumer;

public final class HeartbeatMonitor {

    public enum State {
        DESCONHECIDO, ATIVO, SUSPEITO
    }

    private final int partition;
    private final long timeoutNanos;
    private final Consumer<String> log;

    private State state = State.DESCONHECIDO;

    // Instante local da última receção válida.
    private long lastNanos;

    // Maior termo observado.
    // -1 significa que ainda não foi observado nenhum termo.
    private long term = -1;

    // Identificador do líder observado.
    // 0 significa que ainda não existe líder conhecido.
    private int leader = 0;

    // Último heartbeat aceite no termo atual.
    private long hb = 0;

    public HeartbeatMonitor(
            int partition,
            long periodMs,
            int factor,
            Consumer<String> log) {

        if (partition < 0) {
            throw new IllegalArgumentException(
                "Partição inválida: " + partition);
        }

        if (periodMs <= 0 || factor <= 0) {
            throw new IllegalArgumentException(
                "Período ou fator de timeout inválido");
        }

        if (log == null) {
            throw new IllegalArgumentException(
                "Logger nulo");
        }

        this.partition = partition;

        this.timeoutNanos = Math.multiplyExact(
            Math.multiplyExact(periodMs, (long) factor),
            1_000_000L
        );

        this.log = log;
        this.lastNanos = System.nanoTime();
    }

    /**
     * Thread de receção: chegou um LOTE.
     *
     * term   = termo anunciado pelo líder.
     * leader = identificador do líder (n1, n2 ou n3).
     * hb     = número do heartbeat dentro do termo.
     *
     * Regras:
     * 1. Ignorar termos inferiores.
     * 2. Assinalar NOVO_LIDER quando o termo aumenta.
     * 3. Ignorar heartbeats duplicados ou atrasados.
     * 4. Assinalar HB_PERDIDO apenas dentro do mesmo termo.
     * 5. Assinalar RETOMA após uma suspeita.
     */
    public synchronized void onHeartbeat(
            long term,
            int leader,
            long hb) {

        if (term < 0) {
            throw new IllegalArgumentException(
                "Termo inválido: " + term);
        }

        if (leader < 1 || leader > Wire.GRUPO) {
            throw new IllegalArgumentException(
                "Líder inválido: " + leader);
        }

        if (hb < 1) {
            throw new IllegalArgumentException(
                "Heartbeat inválido: " + hb);
        }

        // 1. Ignorar heartbeats de termos antigos.
        if (term < this.term) {
            return;
        }

        boolean novoTermo = term > this.term;

        if (novoTermo) {

            // 2. Mudança de termo e de líder observado.
            log.accept(
                "NOVO_LIDER;p" + partition
                + ";termo=" + this.term + "->" + term
                + ";lider=n" + leader
            );

        } else {

            // Mesmo termo: não aceitar outro líder.
            if (leader != this.leader) {

                log.accept(
                    "LIDER_CONFLITANTE;p" + partition
                    + ";termo=" + term
                    + ";lider=n" + this.leader
                    + ";recebido=n" + leader
                );

                return;
            }

            // Ignorar heartbeats duplicados ou atrasados.
            if (hb <= this.hb) {
                return;
            }

            // 3. Detetar saltos apenas dentro do mesmo termo.
            if (hb > this.hb + 1) {

                log.accept(
                    "HB_PERDIDO;p" + partition
                    + ";termo=" + term
                    + ";lider=n" + leader
                    + ";esperado=" + (this.hb + 1)
                    + ";recebido=" + hb
                );
            }
        }

        // 4. Retoma depois de uma suspeita.
        if (state == State.SUSPEITO) {

            log.accept(
                "RETOMA;p" + partition
                + ";termo=" + term
                + ";lider=n" + leader
                + ";hb=" + hb
            );
        }

        // Atualizar apenas após aceitar o heartbeat.
        this.term = term;
        this.leader = leader;
        this.hb = hb;

        this.lastNanos = System.nanoTime();
        this.state = State.ATIVO;
    }

    /**
     * Thread de verificação.
     *
     * Chamada a cada Wire.VERIFICACAO_MS.
     *
     * Assinala SUSPEITA quando o silêncio
     * ultrapassa o timeout configurado.
     *
     * Mantém a deteção de silêncio desde
     * o arranque, mesmo antes do primeiro heartbeat.
     */
    public synchronized void check() {

        long elapsed = System.nanoTime() - lastNanos;

        if (state != State.SUSPEITO
                && elapsed > timeoutNanos) {

            state = State.SUSPEITO;

            log.accept(
                "SUSPEITA;p" + partition
                + ";termo=" + term
                + ";lider="
                + (leader == 0 ? "desconhecido" : "n" + leader)
                + ";silencio_ms=" + elapsed / 1_000_000L
            );
        }
    }

    /**
     * Estado atual do monitor.
     */
    public synchronized State state() {
        return state;
    }

    /**
     * Maior termo observado.
     */
    public synchronized long term() {
        return term;
    }

    /**
     * Identificador do líder observado.
     */
    public synchronized int leader() {
        return leader;
    }

    /**
     * Último heartbeat aceite.
     */
    public synchronized long hb() {
        return hb;
    }
}
