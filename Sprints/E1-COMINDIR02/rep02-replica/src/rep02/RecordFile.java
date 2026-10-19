package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Acesso sincronizado a um ficheiro de registos em formato de texto. */
public class RecordFile {
    private final Path path;

    public RecordFile(String fileName) {
        this.path = Paths.get(fileName);
    }

    /** Acrescenta um registo ao ficheiro, criando-o se ainda não existir. */
    public synchronized void append(SensorRecord record) throws IOException {
        Files.writeString(path, record.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Número de sequência do último registo guardado (zero para ficheiro vazio/inexistente). */
    public synchronized long lastSeq() throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        long last = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                last = SensorRecord.fromLine(line).getSeq();
            }
        }
        return last;
    }

    /** Devolve os registos com from <= seq <= to, pela ordem do ficheiro. */
    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> records = new ArrayList<>();
        if (from > to || !Files.exists(path)) {
            return records;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            SensorRecord record = SensorRecord.fromLine(line);
            if (record.getSeq() >= from && record.getSeq() <= to) {
                records.add(record);
            }
        }
        return records;
    }
}
