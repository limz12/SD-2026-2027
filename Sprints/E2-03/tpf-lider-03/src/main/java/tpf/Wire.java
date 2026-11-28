package tpf;

/**
 * Configuração partilhada do projeto TPF.
 *
 * Propriedades JVM:
 *
 * -Dtpf.chunkMaxBytes=<bytes>
 * -Dtpf.padBytes=<bytes>
 * -Dtpf.selagemMs=<ms>
 *
 * Quando uma propriedade não é fornecida,
 * utiliza-se o valor predefinido.
 *
 * Valores inválidos impedem o arranque.
 *
 * CHUNK_MAX_BYTES aplica-se ao conteúdo
 * serializado antes do padding e da cifra.
 */
public final class Wire {

    public static final int CHUNK_MAX_BYTES =
            lerInteiroPositivo("tpf.chunkMaxBytes", 65536);

    public static final int PAD_BYTES =
            lerInteiroPositivo("tpf.padBytes", 4096);

    public static final long SELAGEM_MS =
            lerLongPositivo("tpf.selagemMs", 5000L);

    public static final int PORTO =
            lerInteiroPositivo("tpf.porto", 8765);

    public static final long PERIODO_HB_MS =
            lerLongPositivo("tpf.periodoHbMs", 2000L);

    public static final long SUSPEITA_MS =
            lerLongPositivo("tpf.suspeitaMs", 6000L);

    public static final long MORTA_MS =
            lerLongPositivo("tpf.mortaMs", 10000L);

    public static final long TOKEN_VALIDADE_MS =
            lerLongPositivo("tpf.tokenValidadeMs", 600000L);

    public static final int R =
            lerInteiroPositivo("tpf.r", 3);

    public static final int W_MIN =
            lerInteiroPositivo("tpf.wMin", 2);

    public static final long ACK_TIMEOUT_MS =
            lerLongPositivo("tpf.ackTimeoutMs", 4000L);

    public static final int MAX_RONDAS =
            lerInteiroPositivo("tpf.maxRondas", 3);

    public static final long MARGEM_BYTES =
            lerLongNaoNegativo("tpf.margemBytes", 65536L);

    public static final double ALFA =
            lerDoubleNaoNegativo("tpf.alfa", 1.0);

    public static final long BACKOFF_MIN_MS =
            lerLongPositivo("tpf.backoffMinMs", 1000L);

    public static final long BACKOFF_MAX_MS =
            lerLongPositivo("tpf.backoffMaxMs", 30000L);

    private Wire() {
        // Classe utilitária.
    }

    /**
     * Calcula o quórum de durabilidade para um dado R e W_MIN.
     * A maioria não é uma constante: max(W_MIN, floor(R/2)+1).
     */
    public static int quorumDurabilidade(int r, int wMin) {
        if (r <= 0 || wMin <= 0) {
            throw new IllegalArgumentException(
                "R e W_MIN devem ser inteiros positivos: R=" + r + ", W_MIN=" + wMin
            );
        }
        return Math.max(wMin, (r / 2) + 1);
    }

    public static int quorumDurabilidade() {
        return quorumDurabilidade(R, W_MIN);
    }

    private static int lerInteiroPositivo(
            String propriedade,
            int predefinido) {

        String valor = System.getProperty(propriedade);

        if (valor == null) {
            return predefinido;
        }

        try {
            int numero = Integer.parseInt(valor);

            if (numero <= 0) {
                throw new NumberFormatException();
            }

            return numero;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: "
                + propriedade
                + "='" + valor
                + "' (esperado inteiro positivo)",
                e
            );
        }
    }

    private static long lerLongPositivo(
            String propriedade,
            long predefinido) {

        String valor = System.getProperty(propriedade);

        if (valor == null) {
            return predefinido;
        }

        try {
            long numero = Long.parseLong(valor);

            if (numero <= 0) {
                throw new NumberFormatException();
            }

            return numero;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: "
                + propriedade
                + "='" + valor
                + "' (esperado inteiro positivo)",
                e
            );
        }
    }

    private static long lerLongNaoNegativo(
            String propriedade,
            long predefinido) {

        String valor = System.getProperty(propriedade);

        if (valor == null) {
            return predefinido;
        }

        try {
            long numero = Long.parseLong(valor);

            if (numero < 0) {
                throw new NumberFormatException();
            }

            return numero;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: "
                + propriedade
                + "='" + valor
                + "' (esperado inteiro não negativo)",
                e
            );
        }
    }

    private static double lerDoubleNaoNegativo(
            String propriedade,
            double predefinido) {

        String valor = System.getProperty(propriedade);

        if (valor == null) {
            return predefinido;
        }

        try {
            double numero = Double.parseDouble(valor);

            if (Double.isNaN(numero)
                    || Double.isInfinite(numero)
                    || numero < 0.0) {
                throw new NumberFormatException();
            }

            return numero;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: "
                + propriedade
                + "='" + valor
                + "' (esperado número real não negativo)",
                e
            );
        }
    }
}
