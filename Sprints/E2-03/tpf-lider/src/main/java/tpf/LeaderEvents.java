package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Escrita serializada e durável dos eventos operacionais do líder. */
public final class LeaderEvents {
    private final Path path;

    public LeaderEvents(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
    }

    public synchronized void log(String event) {
        String clean = event.replace('\n', ' ');
        int separator = clean.indexOf(' ');
        String type = separator < 0 ? clean : clean.substring(0, separator);
        String detail = separator < 0 ? "" : clean.substring(separator + 1);
        byte[] line = (System.currentTimeMillis() + ";" + type + ";" + detail + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw new IOException("Escrita de evento sem progresso");
            }
            channel.force(true);
        } catch (IOException e) {
            System.err.println("ERRO ao escrever evento do líder: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
