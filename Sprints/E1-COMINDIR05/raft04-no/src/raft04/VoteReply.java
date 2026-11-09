package raft04;

/**
 * Resposta a um pedido de voto.
 *
 * Formato:
 * VOTO;term;voter;candidate;S|N
 *
 * term      = termo da eleição.
 * voter     = identificador do nó que vota.
 * candidate = identificador do candidato.
 * granted   = true se o voto for concedido;
 *             false se o voto for recusado.
 *
 * Os identificadores dos nós variam
 * entre 1 e Wire.GRUPO.
 *
 * A partição não é incluída na mensagem,
 * porque é identificada pelo tópico de eleição.
 */
public final class VoteReply {

    public final long term;
    public final int voter;
    public final int candidate;
    public final boolean granted;

    /**
     * Construtor de uma resposta de voto.
     */
    public VoteReply(
            long term,
            int voter,
            int candidate,
            boolean granted) {

        if (term < 0) {
            throw new IllegalArgumentException(
                "Termo inválido: " + term
            );
        }

        // Validar o nó eleitor.
        if (voter < 1 || voter > Wire.GRUPO) {
            throw new IllegalArgumentException(
                "Eleitor inválido: " + voter
                + " (deve estar entre 1 e "
                + Wire.GRUPO + ")"
            );
        }

        // Validar o nó candidato.
        if (candidate < 1 || candidate > Wire.GRUPO) {
            throw new IllegalArgumentException(
                "Candidato inválido: " + candidate
                + " (deve estar entre 1 e "
                + Wire.GRUPO + ")"
            );
        }

        this.term = term;
        this.voter = voter;
        this.candidate = candidate;
        this.granted = granted;
    }

    /**
     * Codifica a mensagem VOTO.
     *
     * Formato:
     * VOTO;term;voter;candidate;S|N
     *
     * Exemplos:
     * VOTO;5;3;2;S
     * VOTO;5;1;2;N
     */
    public String encode() {

        return "VOTO;"
                + term + ";"
                + voter + ";"
                + candidate + ";"
                + (granted ? "S" : "N");
    }

    /**
     * Descodifica uma mensagem VOTO.
     *
     * Exige exatamente cinco campos:
     *
     * f[0] = VOTO
     * f[1] = term
     * f[2] = voter
     * f[3] = candidate
     * f[4] = S ou N
     *
     * Apenas S e N são aceites.
     *
     * Lança IllegalArgumentException
     * caso a mensagem seja inválida.
     */
    public static VoteReply decode(String msg) {

        if (msg == null || msg.isBlank()) {
            throw new IllegalArgumentException(
                "Mensagem VOTO vazia ou nula"
            );
        }

        String[] f = msg.trim().split(";", -1);

        // Tipo + 4 campos = 5 campos no total.
        if (f.length != 5 || !f[0].equals("VOTO")) {

            throw new IllegalArgumentException(
                "Mensagem VOTO mal formada: " + msg
            );
        }

        // Validar a resposta antes da conversão.
        if (!f[4].equals("S") && !f[4].equals("N")) {

            throw new IllegalArgumentException(
                "Valor de voto inválido: " + f[4]
                + " (esperado S ou N)"
            );
        }

        try {

            long term = Long.parseLong(f[1]);
            int voter = Integer.parseInt(f[2]);
            int candidate = Integer.parseInt(f[3]);

            boolean granted = f[4].equals("S");

            // O construtor valida os identificadores.
            return new VoteReply(
                term,
                voter,
                candidate,
                granted
            );

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                "Campos numéricos inválidos no VOTO",
                e
            );
        }
    }
}
