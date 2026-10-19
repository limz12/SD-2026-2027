package rep02;

/** Constantes e codificações das mensagens trocadas no sistema COMINDIR02. */
public final class Wire {
    public static final int LIMITE_RETIDOS = 10;
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;

    private Wire() { }

    public static String topicIn(int partition) { return "rep02-in-p" + partition; }
    public static String topicData(int partition) { return "rep02-p" + partition; }

    public static String in(String sensor, double temperature) {
        return "IN;" + sensor + ";" + temperature;
    }

    /** Formato: REG;<host>;<porto>;<seq>;<sensor>;<temperatura>;<instante>. */
    public static String reg(String host, int port, SensorRecord record) {
        return "REG;" + host + ";" + port + ";" + record.toLine();
    }

    /** Tipo da mensagem (por exemplo, IN ou REG). */
    public static String type(String message) {
        if (message == null) {
            throw new IllegalArgumentException("Mensagem nula");
        }
        String trimmed = message.trim();
        int separator = trimmed.indexOf(';');
        if (separator <= 0) {
            throw new IllegalArgumentException("Mensagem sem tipo: " + message);
        }
        return trimmed.substring(0, separator);
    }

    /** Conteúdo da mensagem depois do tipo e do primeiro ponto e vírgula. */
    public static String body(String message) {
        String trimmed = message.trim();
        int separator = trimmed.indexOf(';');
        if (separator < 0) {
            throw new IllegalArgumentException("Mensagem sem conteúdo: " + message);
        }
        return trimmed.substring(separator + 1);
    }

    /** Divide uma REG em {host, porto, linha de SensorRecord}. */
    public static String[] regParts(String message) {
        String[] fields = message.trim().split(";", 4);
        if (fields.length != 4 || !"REG".equals(fields[0])) {
            throw new IllegalArgumentException("REG mal formado: " + message);
        }
        return new String[] { fields[1], fields[2], fields[3] };
    }
}
