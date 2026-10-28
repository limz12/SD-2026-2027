package rep04;

import java.time.LocalDateTime;

/**
 * Representa um registo de sensor.
 *
 * O registo não é Serializable, pois é guardado e transmitido
 * como uma linha de texto, em vez de um objeto Java.
 *
 * Formato:
 * seq;sensor;temperatura;instante;L;V
 *
 * L representa o relógio lógico de Lamport.
 * V representa o relógio vetorial.
 *
 * O líder e as réplicas apenas precisam de conhecer
 * o formato da linha para interpretar os registos.
 */
public class SensorRecord {

    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;

    // Relógios lógicos
    private final long lamport;
    private final VectorClock vector;

    /**
     * Cria um registo com os dados do sensor
     * e os respetivos relógios lógicos.
     *
     * O relógio vetorial é copiado para impedir
     * alterações posteriores ao vetor do registo.
     */
    public SensorRecord(
            long seq,
            String sensorId,
            double temperature,
            String timestamp,
            long lamport,
            VectorClock vector) {

        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;

        this.lamport = lamport;
        this.vector = vector.copy();
    }

    /**
     * Cria um registo com o instante atual.
     * Utilizado pelo líder.
     */
    public static SensorRecord now(
            long seq,
            String sensorId,
            double temperature,
            long lamport,
            VectorClock vector) {

        return new SensorRecord(
            seq,
            sensorId,
            temperature,
            LocalDateTime.now().withNano(0).toString(),
            lamport,
            vector
        );
    }

    /**
     * Converte o registo numa linha de texto.
     *
     * Formato:
     * seq;sensor;temperatura;instante;L;V
     */
    public String toLine() {

        return seq + ";"
                + sensorId + ";"
                + temperature + ";"
                + timestamp + ";"
                + lamport + ";"
                + vector.toString();
    }

    /**
     * Reconstrói um registo a partir de uma linha de texto.
     *
     * N indica o número de partições do sistema
     * e é utilizado para reconstruir o relógio vetorial.
     *
     * Lança IllegalArgumentException quando
     * a linha não apresenta um formato válido.
     */
    public static SensorRecord fromLine(String line, int N) {

        String[] f = line.trim().split(";", -1);

        if (f.length != 6) {
            throw new IllegalArgumentException(
                "Registo mal formado: " + line
            );
        }

        long seq = Long.parseLong(f[0]);
        String sensorId = f[1];
        double temperature = Double.parseDouble(f[2]);
        String timestamp = f[3];

        // Reconstruir o relógio de Lamport
        long lamport = Long.parseLong(f[4]);

        // Reconstruir o relógio vetorial
        VectorClock vector = VectorClock.parse(f[5], N);

        return new SensorRecord(
            seq,
            sensorId,
            temperature,
            timestamp,
            lamport,
            vector
        );
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

    /**
     * Devolve uma cópia do relógio vetorial para
     * preservar o estado interno do registo.
     */
    public VectorClock getVector() {
        return vector.copy();
    }
}
