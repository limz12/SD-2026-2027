package rep05;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Registo de sensor com carimbos de tempo lógico. Linha:
 * seq;sensor;temperatura;instante;L;V
 * L = relógio de Lamport no evento (atribuição do seq pelo líder)
 * V = relógio vetorial no evento; V[k] == seq, em que k é a partição do
 * registo.
 * O ";" separa campos e a "," separa as componentes do vetor, por isso uma
 * linha continua a ter 6 campos.
 */
public class SensorRecord {
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    private final long lamport;
    private final VectorClock vector;

    public SensorRecord(long seq, String sensorId, double temperature, String timestamp,
            long lamport, VectorClock vector) {
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector;
    }

    // cria um registo com o instante atual (usado só pelo líder). O vetor deve ser
    // uma CÓPIA.
    // O instante tem resolução de MILISSEGUNDOS (na COMINDIR03 era de segundos): a
    // latência de lote
    // (receção do lote menos instante do registo) é da ordem de segundos, e uma
    // resolução de 1 s
    // introduziria nela um erro de até 1 s.
    public static SensorRecord now(long seq, String sensorId, double temperature, long lamport, VectorClock vector) {
        return new SensorRecord(seq, sensorId, temperature,
                LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                lamport, vector);
    }

    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }

    // a dimensão do vetor é deduzida da própria linha (as réplicas não precisam de
    // conhecer N)
    public static SensorRecord fromLine(String line) {
        String[] f = line.trim().split(";");
        if (f.length != 6) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return build(f, f[5].split(",").length);
    }

    // idem, mas exige a dimensão n (usado pelo líder e pelo processador, que
    // conhecem N)
    public static SensorRecord fromLine(String line, int n) {
        String[] f = line.trim().split(";");
        if (f.length != 6) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return build(f, n);
    }

    private static SensorRecord build(String[] f, int n) {
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3],
                Long.parseLong(f[4]), VectorClock.parse(f[5], n));
    }

    public long getSeq() {
        return seq;
    }

    public String getSensorId() {
        return sensorId;
    }

    public double getTemperature() {
        return temperature;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public long getLamport() {
        return lamport;
    }

    public VectorClock getVector() {
        return vector;
    }
}