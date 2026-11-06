package raft04;

public final class VoteReply {
    public final long term;
    public final int voter;
    public final int candidate;
    public final boolean granted;

    public VoteReply(long term, int voter, int candidate, boolean granted) {
        this.term = term; this.voter = voter; this.candidate = candidate; this.granted = granted;
    }

    public String encode() {
        return "VOTO;" + term + ";n" + voter + ";n" + candidate + ";" + (granted ? "S" : "N");
    }

    public static VoteReply decode(String msg) {
        String[] f = msg.trim().split(";");
        if (f.length != 5 || !f[0].equals("VOTO")) throw new IllegalArgumentException("VOTO mal formado: " + msg);
        return new VoteReply(Long.parseLong(f[1]), Integer.parseInt(f[2].substring(1)), Integer.parseInt(f[3].substring(1)), f[4].equals("S"));
    }
}
