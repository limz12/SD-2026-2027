package tpf;

public final class Wire {

    public static final long PERIODO_HB_MS = lerLongPositivo("tpf.periodoHbMs", 2000L);
    public static final long BACKOFF_MIN_MS = lerLongPositivo("tpf.backoffMinMs", 1000L);
    public static final long BACKOFF_MAX_MS = lerLongPositivo("tpf.backoffMaxMs", 30000L);

    private Wire() {
    }

    private static long lerLongPositivo(String chave, long omissao) {
        String valor = System.getProperty(chave);
        if (valor == null) {
            return omissao;
        }
        try {
            long parsed = Long.parseLong(valor.trim());
            if (parsed <= 0L) {
                throw new IllegalArgumentException(
                        "Propriedade JVM inválida: " + chave + "=" + valor + " (esperado inteiro longo positivo)");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Propriedade JVM inválida: " + chave + "=" + valor + " (esperado inteiro longo positivo)", e);
        }
    }
}
