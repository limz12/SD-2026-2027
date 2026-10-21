package rep02;

import java.time.LocalDateTime;

/**
 * Um registo de sensor. NÃƒO Ã© Serializable: nÃ£o viaja como objeto Java, mas como
 * uma LINHA DE TEXTO (toLine/fromLine) â€” Ã© isso que se guarda no ficheiro e o que
 * se envia pela rede. Por isso qualquer programa, em qualquer linguagem, o consegue
 * ler (ao contrÃ¡rio da serializaÃ§Ã£o nativa da TCP01).
 *
 * LÃ­der e rÃ©plicas sÃ³ tÃªm de ter em comum o FORMATO da linha
 * (seq;sensor;temperatura;instante), nÃ£o a classe: a classe Ã© sÃ³ uma conveniÃªncia.
 */
public class SensorRecord {
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;

    public SensorRecord(long seq, String sensorId, double temperature, String timestamp) {
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
    }

    // cria um registo com o instante atual (usado sÃ³ pelo lÃ­der)
    public static SensorRecord now(long seq, String sensorId, double temperature) {
        return new SensorRecord(seq, sensorId, temperature, LocalDateTime.now().withNano(0).toString());
    }

    // representaÃ§Ã£o textual: Ã© o que se guarda no ficheiro e o que se envia pela rede
    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp;
    }

    // LanÃ§a IllegalArgumentException (ou a sua subclasse NumberFormatException)
    // se a linha nÃ£o for um registo vÃ¡lido: Ã© com isto que a rÃ©plica decide REJEITADO.
    public static SensorRecord fromLine(String line) {
        String[] f = line.trim().split(";");
        if (f.length != 4) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3]);
    }

    public long getSeq() { return seq; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
}
