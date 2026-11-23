package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class LeaderService {
    private final Path dataDir;
    private final Path logPath;
    private final Path eventPath;
    private final Path seladosDir;
    private final Path masterPath;
    private final ScheduledExecutorService scheduler =
            new ScheduledThreadPoolExecutor(2, r -> {
                Thread t = new Thread(r, "tpf-selagem");
                t.setDaemon(true);
                return t;
            });
    private final byte[] masterKey;
    private long ultimoLsn;
    private Chunk chunk;

    public LeaderService() throws Exception {
        this(Wire.DATA_DIR);
    }

    public LeaderService(Path dataDir) throws Exception {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);
        this.logPath = dataDir.resolve("registos.log");
        this.eventPath = dataDir.resolve("eventos-lider.txt");
        this.seladosDir = dataDir.resolve("selados");
        this.masterPath = dataDir.resolve("master.key");
        Files.createDirectories(seladosDir);
        this.masterKey = carregarOuCriarMasterKey();
        this.ultimoLsn = lerUltimoLsn();
        this.chunk = new Chunk(scheduler, this::selarChunk);
    }

    public synchronized Registo registar(String autor, String canal, String texto) throws Exception {
        String clean = texto == null ? "" : texto.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("texto não pode estar vazio");
        long lsn = ++ultimoLsn;
        Registo registo = new Registo(lsn, System.currentTimeMillis(),
                autor == null ? "desconhecido" : autor, canal, clean);

        String line = lsn + ";" + registo.getInstante() + ";" + autor + ";" + canal + ";"
                + Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(line + System.lineSeparator());
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        adicionarEvento("REGISTO", lsn + ";lsn");

        if (chunk.isSealed()) chunk = new Chunk(scheduler, this::selarChunk);
        chunk.add(registo);
        return registo;
    }

    public synchronized void shutdown() {
        scheduler.shutdownNow();
    }

    public byte[] getMasterKey() {
        return masterKey.clone();
    }

    public Path getLogPath() { return logPath; }
    public Path getEventPath() { return eventPath; }
    public Path getSeladosDir() { return seladosDir; }

    private synchronized void selarChunk(List<Registo> records) {
        try {
            byte[] plaintext = Chunk.serialize(records);
            Sealed sealed = ChunkCrypto.seal(plaintext, masterKey);
            Path bin = seladosDir.resolve(sealed.getChunkId() + ".bin");
            Path hdr = seladosDir.resolve(sealed.getChunkId() + ".hdr");
            escreverFicheiro(bin, sealed.getCiphertext());
            String header = sealed.getKeyVersion() + ";"
                    + Base64.getEncoder().encodeToString(sealed.getSalt()) + ";"
                    + Base64.getEncoder().encodeToString(sealed.getNonce());
            escreverFicheiro(hdr, header.getBytes(StandardCharsets.US_ASCII));

            long primeiro = records.get(0).getLsn();
            long ultimo = records.get(records.size() - 1).getLsn();
            adicionarEvento("SELADO", sealed.getChunkId() + ";LSN " + primeiro + "-" + ultimo
                    + ";tamanho " + sealed.getCiphertext().length);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao selar chunk", e);
        }
    }

    private byte[] carregarOuCriarMasterKey() throws Exception {
        if (!Files.exists(masterPath)) {
            Files.createDirectories(masterPath.getParent());
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            try (FileChannel channel = FileChannel.open(masterPath,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(key);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.setPosixFilePermissions(masterPath,
                        EnumSet.of(PosixFilePermission.OWNER_READ));
            } catch (UnsupportedOperationException | IOException ignored) {
                masterPath.toFile().setReadable(true, true);
                masterPath.toFile().setWritable(false, false);
                masterPath.toFile().setWritable(true, true);
            }
            Arrays.fill(key, (byte) 0);
            return Files.readAllBytes(masterPath);
        }

        byte[] key = Files.readAllBytes(masterPath);
        if (key.length != 32) throw new IllegalStateException("master.key deve ter exatamente 32 bytes");
        return key;
    }

    private long lerUltimoLsn() throws IOException {
        if (!Files.exists(logPath)) return 0;
        long last = 0;
        for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            int separator = line.indexOf(';');
            if (separator <= 0) throw new IOException("linha inválida em registos.log: " + line);
            try {
                last = Math.max(last, Long.parseLong(line.substring(0, separator)));
            } catch (NumberFormatException e) {
                throw new IOException("LSN inválido em registos.log", e);
            }
        }
        return last;
    }

    private synchronized void adicionarEvento(String evento, String detalhe) throws IOException {
        String line = System.currentTimeMillis() + ";" + evento + ";" + detalhe;
        try (FileChannel channel = FileChannel.open(eventPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(line + System.lineSeparator());
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private void escreverFicheiro(Path target, byte[] content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
