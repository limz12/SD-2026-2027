
package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class RecordFile {

    private final Path path;
    private final int N;

    public RecordFile(String fileName, int N) {

        if (N <= 0) {
            throw new IllegalArgumentException(
                "Número de partições inválido: " + N
            );
        }

        this.path = Paths.get(fileName);
        this.N = N;
    }

    /**
     * Acrescenta um registo ao fim do ficheiro.
     * Cria o ficheiro caso ainda não exista.
     */
    public synchronized void append(SensorRecord r)
            throws IOException {

        Files.writeString(
            path,
            r.toLine() + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    /**
     * Devolve o último registo guardado.
     *
     * Se o ficheiro não existir ou estiver vazio,
     * devolve null.
     */
    public synchronized SensorRecord last()
            throws IOException {

        if (!Files.exists(path)) {
            return null;
        }

        SensorRecord ultimo = null;

        for (String line : Files.readAllLines(
                path, StandardCharsets.UTF_8)) {

            if (!line.isBlank()) {

                ultimo = SensorRecord.fromLine(line, N);
            }
        }

        return ultimo;
    }

    /**
     * Devolve o número de sequência do último registo.
     *
     * Devolve 0 se o ficheiro não existir
     * ou não contiver registos.
     */
    public synchronized long lastSeq()
            throws IOException {

        SensorRecord ultimo = last();

        return ultimo == null ? 0 : ultimo.getSeq();
    }

    /**
     * Devolve os registos com:
     *
     * from <= seq <= to
     *
     * Os registos são devolvidos pela ordem do ficheiro.
     * Se o ficheiro não existir, devolve uma lista vazia.
     */
    public synchronized List<SensorRecord> range(
            long from, long to) throws IOException {

        List<SensorRecord> out = new ArrayList<>();

        if (!Files.exists(path)) {
            return out;
        }

        for (String line : Files.readAllLines(
                path, StandardCharsets.UTF_8)) {

            if (line.isBlank()) {
                continue;
            }

            SensorRecord r = SensorRecord.fromLine(line, N);

            if (r.getSeq() >= from && r.getSeq() <= to) {
                out.add(r);
            }
        }

        return out;
    }
}
