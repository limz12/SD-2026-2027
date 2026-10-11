package rep01;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class RecordFile {
    private final Path path;
    public RecordFile(String fileName) { path = Paths.get(fileName); }
    public synchronized void append(SensorRecord r) throws IOException {
        Files.writeString(path, r.toLine() + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    public synchronized long lastSeq() throws IOException {
        if (!Files.exists(path)) return 0;
        long last = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) if (!line.isBlank()) last = SensorRecord.fromLine(line).getSeq();
        return last;
    }
}
