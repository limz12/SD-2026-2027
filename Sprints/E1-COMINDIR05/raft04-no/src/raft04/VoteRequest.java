package raft04;

public final class VoteRequest {
    public final long term;
    public final int candidate;
    public final long lastSeq;
    public final long lastTerm;

    public VoteRequest(long term, int candidate, long lastSeq, long lastTerm) {
        this.term = term; this.candidate = candidate; this.lastSeq = lastSeq; this.lastTerm = lastTerm;
    }

    public String encode() {
        return "PEDIDO_VOTO;" + term + ";n" + candidate + ";" + lastSeq + ";" + lastTerm;
    }

    public static VoteRequest decode(String msg) {
        String[] f = msg.trim().split(";");
        if (f.length != 5 || !f[0].equals("PEDIDO_VOTO")) throw new IllegalArgumentException("PEDIDO_VOTO mal formado: " + msg);
        return new VoteRequest(Long.parseLong(f[1]), Integer.parseInt(f[2].substring(1)), Long.parseLong(f[3]), Long.parseLong(f[4]));
    }
}
