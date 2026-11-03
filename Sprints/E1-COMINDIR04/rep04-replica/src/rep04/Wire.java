package rep04;

public final class Wire {

    // Número máximo de registos retidos antes da recuperação
    public static final int LIMITE_RETIDOS = 10;

    // Tempo máximo de ligação TCP e espera por cada linha
    public static final int T_TCP_MS = 5000;

    // Porto de recuperação do líder da partição k
    public static final int PORTO_BASE = 7000;

    // Limiar de temperatura para geração de alarmes
    public static final float LIMIAR_ALARME = 28.0f;

    // Período de envio de heartbeats (por omissão 5000 ms)
    public static final long PERIODO_HB_MS =
            Long.getLong("rep04.periodo", 5000L);

    // Fator multiplicador para determinar o timeout
    public static final int FATOR_TIMEOUT =
            Integer.getInteger("rep04.fator", 3);

    // Intervalo de verificação dos heartbeats
    public static final int VERIFICACAO_MS = 1000;

    private Wire() { }

    /**
     * Tópico de entrada da partição k.
     * Recebe mensagens IN.
     */
    public static String topicIn(int k) {
        return "rep04-in-p" + k;
    }

    /**
     * Tópico de dados da partição k.
     * Recebe mensagens LOTE codificadas por Batch.encode().
     */
    public static String topicData(int k) {
        return "rep04-p" + k;
    }

    /**
     * Constrói uma mensagem de entrada.
     *
     * Formato:
     * IN;sensor;temperatura;L;V
     */
    public static String in(
            String sensor,
            double temp,
            long lamport,
            VectorClock vc) {

        return "IN;" + sensor
                + ";" + temp
                + ";" + lamport
                + ";" + vc.toString();
    }

    /**
     * Obtém o tipo de uma mensagem.
     *
     * Tipos utilizados:
     * IN   - leitura de sensor
     * LOTE - lote de registos e informação do líder
     *
     * Lança IllegalArgumentException
     * se a mensagem não tiver um tipo válido.
     */
    public static String type(String msg) {

        String m = msg.trim();

        int i = m.indexOf(';');

        if (i <= 0) {
            throw new IllegalArgumentException(
                "Mensagem sem tipo: " + msg
            );
        }

        return m.substring(0, i);
    }

    /**
     * Devolve o conteúdo da mensagem,
     * removendo o tipo inicial e o primeiro ';'.
     */
    public static String body(String msg) {

        String m = msg.trim();

        int i = m.indexOf(';');

        if (i <= 0) {
            throw new IllegalArgumentException(
                "Mensagem sem tipo: " + msg
            );
        }

        return m.substring(i + 1);
    }
}
