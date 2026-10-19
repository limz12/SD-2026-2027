package rep02;

import java.time.LocalDateTime;

/**
 * Registo de sensor representado em texto, para que todos os processos
 * partilhem o formato seq;sensor;temperatura;instante.
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

    /** Cria um registo com o instante local atual. */
    public static SensorRecord now(long seq, String sensorId, double temperature) {
        return new SensorRecord(seq, sensorId, temperature,
                LocalDateTime.now().withNano(0).toString());
    }

    /** Linha persistida nos ficheiros e usada nos protocolos de texto. */
    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp;
    }

    /** Constrói um registo a partir da linha persistida ou recebida pela rede. */
    public static SensorRecord fromLine(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Linha de registo nula");
        }
        String[] fields = line.trim().split(";", -1);
        if (fields.length != 4 || fields[1].isBlank() || fields[3].isBlank()) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        long seq = Long.parseLong(fields[0]);
        double temperature = Double.parseDouble(fields[2]);
        if (seq < 1 || !Double.isFinite(temperature)) {
            throw new IllegalArgumentException("Valores inválidos no registo: " + line);
        }
        return new SensorRecord(seq, fields[1], temperature, fields[3]);
    }

    public long getSeq() { return seq; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
}
