package raft04;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Registo de sensor com carimbos de tempo lógico. Linha (COMINDIR05):
 * seq;termo;sensor;temperatura;instante;L;V
 *
 * termo = termo de liderança em que o seq foi atribuído. Dois registos com o
 * mesmo seq e o mesmo termo são o mesmo registo; com termos diferentes,
 * prevalece o do líder atual (o mais recente).
 *
 * L = relógio de Lamport no evento (atribuição do seq pelo líder)
 * V = relógio vetorial no evento; V[k] == seq, em que k é a partição do
 * registo.
 * O ";" separa campos e a "," separa as componentes do vetor, por isso uma
 * linha tem 7 campos.
 */
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
        this.seq = seq;
        this.term = term;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector;
    }

    // cria um registo com o instante atual (usado só pelo líder). O vetor deve ser
    // uma CÓPIA. O instante tem resolução de MILISSEGUNDOS: a latência de lote
    // (receção do lote menos instante do registo) é da ordem de segundos, e uma
    // resolução de 1 s introduziria nela um erro de até 1 s.
    public static SensorRecord now(long seq, long term, String sensorId, double temperature,
            long lamport, VectorClock vector) {
        return new SensorRecord(seq, term, sensorId, temperature,
                LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                lamport, vector);
    }

    public String toLine() {
        return seq + ";" + term + ";" + sensorId + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }

    // a dimensão do vetor é deduzida da própria linha
    public static SensorRecord fromLine(String line) {
        String[] f = line.trim().split(";");
        if (f.length != 7) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return build(f, f[6].split(",").length);
    }

    // idem, mas exige a dimensão n (usado pelos nós e pelo processador, que
    // conhecem N)
    public static SensorRecord fromLine(String line, int n) {
        String[] f = line.trim().split(";");
        if (f.length != 7) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return build(f, n);
    }

    private static SensorRecord build(String[] f, int n) {
        return new SensorRecord(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2], Double.parseDouble(f[3]), f[4],
                Long.parseLong(f[5]), VectorClock.parse(f[6], n));
    }

    public long getSeq() {
        return seq;
    }

    public long getTerm() {
        return term;
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
