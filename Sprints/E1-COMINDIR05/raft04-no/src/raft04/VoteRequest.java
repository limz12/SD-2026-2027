package raft04;

/**
 * Pedido de voto enviado por um nó candidato.
 *
 * Formato:
 * PEDIDO_VOTO;term;candidate;lastSeq;lastTerm
 *
 * term      = termo da eleição.
 * candidate = identificador do candidato (1 a GRUPO).
 * lastSeq   = última sequência do log do candidato.
 * lastTerm  = termo do último registo do log.
 *
 * A partição não é incluída na mensagem,
 * porque é identificada pelo tópico de eleição.
 */
public final class VoteRequest {

    public final long term;
    public final int candidate;
    public final long lastSeq;
    public final long lastTerm;

    /**
     * Construtor do pedido de voto.
     */
    public VoteRequest(
            long term,
            int candidate,
            long lastSeq,
            long lastTerm) {

        if (term < 0) {
            throw new IllegalArgumentException(
                "Termo inválido: " + term
            );
        }

        // Identificadores válidos: n1, n2 e n3.
        if (candidate < 1 || candidate > Wire.GRUPO) {
            throw new IllegalArgumentException(
                "Candidato inválido: " + candidate
                + " (deve estar entre 1 e "
                + Wire.GRUPO + ")"
            );
        }

        if (lastSeq < 0 || lastTerm < 0) {
            throw new IllegalArgumentException(
                "Última sequência ou último termo inválidos"
            );
        }

        this.term = term;
        this.candidate = candidate;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
    }

    /**
     * Codifica a mensagem PEDIDO_VOTO.
     *
     * Formato:
     * PEDIDO_VOTO;term;candidate;lastSeq;lastTerm
     *
     * Exemplo:
     * PEDIDO_VOTO;5;2;12;4
     */
    public String encode() {

        return "PEDIDO_VOTO;"
                + term + ";"
                + candidate + ";"
                + lastSeq + ";"
                + lastTerm;
    }

    /**
     * Descodifica uma mensagem PEDIDO_VOTO.
     *
     * Exige exatamente cinco campos:
     *
     * f[0] = PEDIDO_VOTO
     * f[1] = term
     * f[2] = candidate
     * f[3] = lastSeq
     * f[4] = lastTerm
     *
     * Lança IllegalArgumentException
     * caso a mensagem seja inválida.
     */
    public static VoteRequest decode(String msg) {

        if (msg == null || msg.isBlank()) {
            throw new IllegalArgumentException(
                "Mensagem PEDIDO_VOTO vazia ou nula"
            );
        }

        String[] f = msg.trim().split(";", -1);

        // Tipo + 4 campos = 5 campos no total.
        if (f.length != 5
                || !f[0].equals("PEDIDO_VOTO")) {

            throw new IllegalArgumentException(
                "Mensagem PEDIDO_VOTO mal formada: " + msg
            );
        }

        try {

            long term = Long.parseLong(f[1]);
            int candidate = Integer.parseInt(f[2]);
            long lastSeq = Long.parseLong(f[3]);
            long lastTerm = Long.parseLong(f[4]);

            // O construtor valida os valores.
            return new VoteRequest(
                term,
                candidate,
                lastSeq,
                lastTerm
            );

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                "Campos numéricos inválidos no PEDIDO_VOTO",
                e
            );
        }
    }
}
