import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class RecordFile {
    private final Path path;

    public RecordFile(String filename) {
        this.path = Paths.get(filename);
    }

    public synchronized long lastSeq() throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        long last = 0;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    try {
                        SensorRecord r = SensorRecord.fromLine(line);
                        if (r.getSeq() > last) {
                            last = r.getSeq();
                        }
                    } catch (IllegalArgumentException e) {
                        // Ignora linhas corrompidas antigas se necessário, ou deixa propagar
                    }
                }
            }
        }
        return last;
    }

    public synchronized void append(SensorRecord r) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(r.toLine());
            writer.newLine();
        }
    }

    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> result = new ArrayList<>();
        if (!Files.exists(path)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    try {
                        SensorRecord r = SensorRecord.fromLine(line);
                        if (r.getSeq() >= from && r.getSeq() <= to) {
                            result.add(r);
                        }
                    } catch (IllegalArgumentException e) {
                        // Ignora registos mal formados na leitura do range
                    }
                }
            }
        }
        return result;
    }
}