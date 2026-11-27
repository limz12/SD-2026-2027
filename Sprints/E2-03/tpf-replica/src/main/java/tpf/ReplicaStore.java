package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/** Persistência atómica dos bytes cifrados e controlo da quota local. */
final class ReplicaStore {
    enum Result { STORED, EXISTS, QUOTA }

    private final Path chunks;
    private final long quota;
    private long used;

    ReplicaStore(Path chunks, long quota) throws IOException {
        this.chunks = chunks;
        this.quota = quota;
        Files.createDirectories(chunks);
        try (var files = Files.list(chunks)) {
            used = files.filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .mapToLong(path -> {
                        try { return Files.size(path); }
                        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    }).sum();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    synchronized Result store(String id, byte[] header, byte[] bin) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}") || header == null || header.length == 0 || bin == null) {
            throw new IOException("STORE inválido");
        }
        Path binPath = chunks.resolve(id + ".bin");
        Path hdrPath = chunks.resolve(id + ".hdr");
        boolean hasBin = Files.exists(binPath);
        boolean hasHeader = Files.exists(hdrPath);

        if (hasBin) {
            byte[] existingBin = Files.readAllBytes(binPath);
            if (!sha256(existingBin).equals(id) || !java.util.Arrays.equals(existingBin, bin)) {
                throw new IOException("Cópia .bin existente contraditória");
            }
        }
        if (hasHeader && !java.util.Arrays.equals(Files.readAllBytes(hdrPath), header)) {
            throw new IOException("Cabeçalho existente contraditório");
        }
        if (hasBin && hasHeader) return Result.EXISTS;
        if (!hasBin && (bin.length > quota || used > quota - bin.length)) return Result.QUOTA;
        if (!sha256(bin).equals(id)) throw new IOException("Hash do .bin não corresponde ao chunkId");

        if (!hasBin) writeAtomic(binPath, bin);
        if (!hasHeader) writeAtomic(hdrPath, header);
        if (!hasBin) used = Math.addExact(used, bin.length);
        return Result.STORED;
    }

    synchronized long used() { return used; }

    private static void writeAtomic(Path destination, byte[] bytes) throws IOException {
        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw new IOException("Escrita temporária sem progresso");
            }
            channel.force(true);
        }
        Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 indisponível", e);
        }
    }
}
