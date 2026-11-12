package raft04;

/**
 * VOTO;<termo>;<eleitor>;<candidato>;<S|N>
 *
 * Resposta a um PEDIDO_VOTO. É enviada MESMO quando o voto é recusado (N): o
 * termo da resposta pode ser superior ao do candidato e força-o a adotá-lo e a
 * demitir-se.
 */
public final class VoteReply {
    public final long term;
    public final int eleitor;
    public final int candidate;
    public final boolean granted;

    public VoteReply(long term, int eleitor, int candidate, boolean granted) {
        this.term = term;
        this.eleitor = eleitor;
        this.candidate = candidate;
        this.granted = granted;
    }

    public String encode() {
        return "VOTO;" + term + ";" + eleitor + ";" + candidate + ";" + (granted ? "S" : "N");
    }

    public static VoteReply decode(String msg) {
        String[] f = Wire.body(msg).split(";");
        if (f.length != 4 || !(f[3].equals("S") || f[3].equals("N"))) {
            throw new IllegalArgumentException("VOTO mal formado: " + msg);
        }
        return new VoteReply(Long.parseLong(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3].equals("S"));
    }
}
