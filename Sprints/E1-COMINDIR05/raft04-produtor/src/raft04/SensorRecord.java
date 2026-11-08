package raft04;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class SensorRecord {
    private final long seq;
    private final long term;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    private final long lamport;
    private final VectorClock vector;

    public SensorRecord(long seq, long term, String sensorId, double temperature, String timestamp,
            long lamport, VectorClock vector) {
        if (seq < 1 || term < 0 || sensorId == null || sensorId.isBlank()
                || !Double.isFinite(temperature) || timestamp == null || timestamp.isBlank()
                || lamport < 0 || vector == null) {
            throw new IllegalArgumentException("Campos do registo fora dos limites");
        }
        this.seq = seq;
        this.term = term;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector;
    }

    public static SensorRecord now(long seq, long term, String sensorId, double temperature,
            long lamport, VectorClock vector) {
        return new SensorRecord(seq, term, sensorId, temperature,
                LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString(), lamport, vector);
    }

    public String toLine() {
        return seq + ";" + term + ";" + sensorId + ";" + temperature + ";" + timestamp + ";"
                + lamport + ";" + vector;
    }

    public static SensorRecord fromLine(String line) {
        String[] fields = line.trim().split(";", -1);
        if (fields.length != 7) throw new IllegalArgumentException("Registo mal formado: " + line);
        return build(fields, fields[6].split(",").length);
    }

    public static SensorRecord fromLine(String line, int n) {
        String[] fields = line.trim().split(";", -1);
        if (fields.length != 7) throw new IllegalArgumentException("Registo mal formado: " + line);
        return build(fields, n);
    }

    private static SensorRecord build(String[] f, int n) {
        return new SensorRecord(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2],
                Double.parseDouble(f[3]), f[4], Long.parseLong(f[5]), VectorClock.parse(f[6], n));
    }

    public long getSeq() { return seq; }
    public long getTerm() { return term; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector; }
}
