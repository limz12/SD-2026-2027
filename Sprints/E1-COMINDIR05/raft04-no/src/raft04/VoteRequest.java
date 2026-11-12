package raft04;

/**
 * PEDIDO_VOTO;<termo>;<candidato>;<ultimoSeq>;<ultimoTermo>
 *
 * Publicado por um nó que acaba de se tornar candidato (tick() devolveu o termo).
 * O candidato indica o seq e o termo do seu último registo: é a restrição de
 * voto que impede a eleição de um nó a quem faltam registos que outros já têm.
 */
public final class VoteRequest {
    public final long term;
    public final int candidate;
    public final long lastSeq;
    public final long lastTerm;

    public VoteRequest(long term, int candidate, long lastSeq, long lastTerm) {
        this.term = term;
        this.candidate = candidate;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
    }

    public String encode() {
        return "PEDIDO_VOTO;" + term + ";" + candidate + ";" + lastSeq + ";" + lastTerm;
    }

    public static VoteRequest decode(String msg) {
        String[] f = Wire.body(msg).split(";");
        if (f.length != 4) {
            throw new IllegalArgumentException("PEDIDO_VOTO mal formado: " + msg);
        }
        return new VoteRequest(Long.parseLong(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2]),
                Long.parseLong(f[3]));
    }
}
