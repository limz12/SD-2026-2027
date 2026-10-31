package rep04;

public final class Wire {
    public static final int LIMITE_RETIDOS = 10;   // acima deste número de retidos, a réplica recupera
    public static final int T_TCP_MS = 5000;       // tempo máximo de ligação e de espera por cada linha
    public static final int PORTO_BASE = 7000;    // porto de recuperação do líder da partição k: 7000 + k
    public static final float LIMIAR_ALARME = 28.0f;   // limiar de alarme para a temperatura

    // COMINDIR04: batimentos (heartbeats) e deteção de falhas
    public static final long PERIODO_HB_MS  = Long.getLong("rep04.periodo", 5000);      // período dos batimentos do líder
    public static final int  FATOR_TIMEOUT  = Integer.getInteger("rep04.fator", 3);     // TIMEOUT = FATOR x PERÍODO
    public static final long VERIFICACAO_MS = 1000;     // período da thread de verificação de suspeitas

    private Wire() { }

    public static String topicIn(int k)   { return "rep04-in-p" + k; }
    public static String topicData(int k) { return "rep04-p" + k; }

    public static String in(String sensor, double temp, long Lamport, VectorClock vc) { return "IN;" + sensor + ";" + temp + ";" + Lamport + ";" + vc.toString(); }

    // tipo da mensagem (IN, LOTE); lança IllegalArgumentException se não houver tipo
    public static String type(String msg) {
        String m = msg.trim();
        int i = m.indexOf(';');
        if (i <= 0) {
            throw new IllegalArgumentException("Mensagem sem tipo: " + msg);
        }
        return m.substring(0, i);
    }

    // conteúdo da mensagem sem o tipo
    public static String body(String msg) {
        String m = msg.trim();
        return m.substring(m.indexOf(';') + 1);
    }

    // divide uma mensagem REG em { endereço, porto, linha do registo }
    public static String[] regParts(String msg) {
        String[] f = msg.trim().split(";", 4);
        if (f.length != 4 || !f[0].equals("REG")) {
            throw new IllegalArgumentException("REG mal formado: " + msg);
        }
        return new String[] { f[1], f[2], f[3] };
    }
}