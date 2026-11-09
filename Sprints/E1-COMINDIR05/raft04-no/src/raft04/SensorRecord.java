
package raft04;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Registo de sensor com termo de eleição
 * e carimbos de tempo lógico.
 *
 * Formato:
 *
 * seq;termo;sensor;temperatura;instante;L;V
 *
 * seq:
 *   Número de sequência do registo.
 *
 * termo:
 *   Termo em que o registo foi criado.
 *
 * sensor:
 *   Identificador do sensor.
 *
 * temperatura:
 *   Temperatura registada.
 *
 * instante:
 *   Instante de criação do registo,
 *   com precisão de milissegundos.
 *
 * L:
 *   Relógio lógico de Lamport.
 *
 * V:
 *   Relógio vetorial.
 *
 * O vetor é separado por vírgulas.
 *
 * Cada linha contém exatamente 7 campos.
 */
public class SensorRecord {

    private final long seq;
    private final long term;

    private final String sensorId;
    private final double temperature;
    private final String timestamp;

    private final long lamport;
    private final VectorClock vector;

    /**
     * Construtor de um registo.
     */
    public SensorRecord(
            long seq,
            long term,
            String sensorId,
            double temperature,
            String timestamp,
            long lamport,
            VectorClock vector) {

        if (seq <= 0) {
            throw new IllegalArgumentException(
                "Sequência inválida: " + seq
            );
        }

        if (term < 0) {
            throw new IllegalArgumentException(
                "Termo inválido: " + term
            );
        }

        if (sensorId == null
                || sensorId.isBlank()
                || sensorId.contains(";")
                || sensorId.contains("\n")
                || sensorId.contains("\r")) {

            throw new IllegalArgumentException(
                "Identificador de sensor inválido"
            );
        }

        if (!Double.isFinite(temperature)) {
            throw new IllegalArgumentException(
                "Temperatura inválida"
            );
        }

        if (timestamp == null
                || timestamp.isBlank()) {

            throw new IllegalArgumentException(
                "Instante inválido"
            );
        }

        if (lamport < 0) {
            throw new IllegalArgumentException(
                "Relógio de Lamport inválido"
            );
        }

        if (vector == null) {
            throw new IllegalArgumentException(
                "Relógio vetorial nulo"
            );
        }

        // Validar o formato do instante
        LocalDateTime.parse(timestamp);

        this.seq = seq;
        this.term = term;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;

        // Cópia defensiva do relógio vetorial
        this.vector = vector.copy();
    }

    /**
     * Cria um registo com o instante atual.
     *
     * O termo é o termo atual do líder
     * no momento em que atribui a sequência.
     *
     * O instante conserva os milissegundos.
     */
    public static SensorRecord now(
            long seq,
            long term,
            String sensorId,
            double temperature,
            long lamport,
            VectorClock vector) {

        String instante = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS)
                .format(
                    DateTimeFormatter.ofPattern(
                        "uuuu-MM-dd'T'HH:mm:ss.SSS"
                    )
                );

        return new SensorRecord(
            seq,
            term,
            sensorId,
            temperature,
            instante,
            lamport,
            vector
        );
    }

    /**
     * Converte o registo para o formato
     * utilizado nos ficheiros e na rede.
     *
     * Formato:
     * seq;termo;sensor;temperatura;instante;L;V
     */
    public String toLine() {

        return seq + ";"
                + term + ";"
                + sensorId + ";"
                + temperature + ";"
                + timestamp + ";"
                + lamport + ";"
                + vector;
    }

    /**
     * Reconstrói um registo a partir de uma linha.
     *
     * A dimensão do vetor é deduzida
     * da própria representação textual.
     *
     * Útil para os nós que não conhecem N.
     */
    public static SensorRecord fromLine(String line) {

        if (line == null) {
            throw new IllegalArgumentException(
                "Registo nulo"
            );
        }

        String[] f = line.trim().split(";", -1);

        if (f.length != 7) {
            throw new IllegalArgumentException(
                "Registo mal formado: " + line
            );
        }

        // O vetor encontra-se no campo 6
        int n = f[6].split(",", -1).length;

        return build(f, n);
    }

    /**
     * Reconstrói um registo exigindo
     * uma dimensão específica do vetor.
     *
     * Utilizado pelos processos que conhecem N.
     */
    public static SensorRecord fromLine(
            String line,
            int n) {

        if (line == null) {
            throw new IllegalArgumentException(
                "Registo nulo"
            );
        }

        if (n <= 0) {
            throw new IllegalArgumentException(
                "Dimensão do vetor inválida: " + n
            );
        }

        String[] f = line.trim().split(";", -1);

        if (f.length != 7) {
            throw new IllegalArgumentException(
                "Registo mal formado: " + line
            );
        }

        return build(f, n);
    }

    /**
     * Reconstrói o registo a partir
     * dos campos já separados.
     */
    private static SensorRecord build(
            String[] f,
            int n) {

        try {

            long seq = Long.parseLong(f[0]);
            long term = Long.parseLong(f[1]);

            String sensorId = f[2];

            double temperature =
                    Double.parseDouble(f[3]);

            String timestamp = f[4];

            long lamport = Long.parseLong(f[5]);

            VectorClock vector = VectorClock.parse(
                f[6],
                n
            );

            return new SensorRecord(
                seq,
                term,
                sensorId,
                temperature,
                timestamp,
                lamport,
                vector
            );

        } catch (IllegalArgumentException e) {

            throw new IllegalArgumentException(
                "Registo inválido: "
                + String.join(";", f),
                e
            );
        }
    }

    /**
     * Número de sequência.
     */
    public long getSeq() {
        return seq;
    }

    /**
     * Termo em que o registo foi criado.
     */
    public long getTerm() {
        return term;
    }

    /**
     * Identificador do sensor.
     */
    public String getSensorId() {
        return sensorId;
    }

    /**
     * Temperatura.
     */
    public double getTemperature() {
        return temperature;
    }

    /**
     * Instante de criação.
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Relógio de Lamport.
     */
    public long getLamport() {
        return lamport;
    }

    /**
     * Devolve uma cópia do vetor,
     * evitando alterações externas.
     */
    public VectorClock getVector() {
        return vector.copy();
    }

    /**
     * Representação textual do registo.
     */
    @Override
    public String toString() {
        return toLine();
    }
}
