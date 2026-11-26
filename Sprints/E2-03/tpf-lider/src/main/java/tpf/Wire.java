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

    private Wire() {
        // Classe utilitária.
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
}
