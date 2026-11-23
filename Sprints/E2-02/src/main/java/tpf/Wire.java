package tpf;

/**
 * Constantes "na rede" e no disco, alteraveis com propriedades de sistema (-Dtpf.*).
 *
 *   -Dtpf.chunkMaxBytes=65536   limite de tamanho de um chunk selado
 *   -Dtpf.padBytes=4096         multiplo de alinhamento do texto em claro
 *   -Dtpf.selagemMs=5000        tempo ate selar o chunk aberto
 *
 * Os valores sao lidos UMA VEZ, quando esta classe e carregada. Nos testes, as
 * propriedades sao definidas antes da primeira utilizacao de Wire.
 */
public final class Wire {

    /** Limite de tamanho (em bytes) do ficheiro .bin de um chunk selado. */
    public static final int CHUNK_MAX_BYTES;

    /** Multiplo de alinhamento: o texto cifrado tem sempre tamanho multiplo disto. */
    public static final int PAD_BYTES;

    /** Tempo (ms) entre o primeiro registo do chunk aberto e a selagem por tempo. */
    public static final long SELAGEM_MS;

    static {
        int pad = lerInteiro("tpf.padBytes", 4096);
        if (pad < 16 || pad > (1 << 20) || pad % 16 != 0) {
            throw new IllegalStateException("tpf.padBytes tem de estar entre 16 e 1048576 e ser multiplo de 16 (recebi " + pad + ").");
        }
        int max = lerInteiro("tpf.chunkMaxBytes", 65536);
        if (max < 2 * pad || max > (1 << 30)) {
            throw new IllegalStateException("tpf.chunkMaxBytes tem de estar entre " + (2 * pad) + " e 1073741824 (recebi " + max + ").");
        }
        long ms = lerLong("tpf.selagemMs", 5000L);
        if (ms < 0 || ms > 3_600_000L) {
            throw new IllegalStateException("tpf.selagemMs tem de estar entre 0 e 3600000 (recebi " + ms + ").");
        }
        PAD_BYTES = pad;
        CHUNK_MAX_BYTES = max;
        SELAGEM_MS = ms;
    }

    private Wire() {
    }

    private static int lerInteiro(String nome, int valorPorOmissao) {
        String v = System.getProperty(nome);
        if (v == null || v.trim().isEmpty()) {
            return valorPorOmissao;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Propriedade " + nome + " nao e um inteiro valido: \"" + v + "\".");
        }
    }

    private static long lerLong(String nome, long valorPorOmissao) {
        String v = System.getProperty(nome);
        if (v == null || v.trim().isEmpty()) {
            return valorPorOmissao;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Propriedade " + nome + " nao e um numero valido: \"" + v + "\".");
        }
    }
}
