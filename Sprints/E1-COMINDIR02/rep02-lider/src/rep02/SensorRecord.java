package rep02;

import java.time.LocalDateTime;

/**
 * Um registo de sensor. NÃO é Serializable: não viaja como objeto Java, mas como
 * uma LINHA DE TEXTO (toLine/fromLine) — é isso que se guarda no ficheiro e o que
 * se envia pela rede. Por isso qualquer programa, em qualquer linguagem, o consegue
 * ler (ao contrário da serialização nativa da TCP01).
 *
 * Líder e réplicas só têm de ter em comum o FORMATO da linha
 * (seq;sensor;temperatura;instante), não a classe: a classe é só uma conveniência.
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

    // cria um registo com o instante atual (usado só pelo líder)
    public static SensorRecord now(long seq, String sensorId, double temperature) {
        return new SensorRecord(seq, sensorId, temperature, LocalDateTime.now().withNano(0).toString());
    }

    // representação textual: é o que se guarda no ficheiro e o que se envia pela rede
    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp;
    }

    // Lança IllegalArgumentException (ou a sua subclasse NumberFormatException)
    // se a linha não for um registo válido: é com isto que a réplica decide REJEITADO.
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