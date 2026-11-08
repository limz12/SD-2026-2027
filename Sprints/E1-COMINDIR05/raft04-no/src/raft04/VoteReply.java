package raft04;

public final class VoteReply {
    public final long term;
    public final int voter;
    public final int candidate;
    public final boolean granted;

    public VoteReply(long term, int voter, int candidate, boolean granted) {
        if (term < 0 || voter < 1 || candidate < 1) {
            throw new IllegalArgumentException("Campos de VOTO fora dos limites");
        }
        this.term = term;
        this.voter = voter;
        this.candidate = candidate;
        this.granted = granted;
    }

    public String encode() {
        return "VOTO;" + term + ";" + voter + ";" + candidate + ";" + (granted ? "S" : "N");
    }

    public static VoteReply decode(String message) {
        String[] fields = message.trim().split(";", -1);
        if (fields.length != 5 || !"VOTO".equals(fields[0])
                || !("S".equals(fields[4]) || "N".equals(fields[4]))) {
            throw new IllegalArgumentException("VOTO inválido: " + message);
        }
        try {
            return new VoteReply(Long.parseLong(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), "S".equals(fields[4]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("VOTO inválido: " + message, e);
        }
    }
}
