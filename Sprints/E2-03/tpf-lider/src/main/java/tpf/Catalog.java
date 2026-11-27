package tpf;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catálogo persistente de colocações confirmadas. Todos os métodos são sincronizados. */
public final class Catalog {
    private final Path path;
    private final Map<String, EntradaCatalogo> entries = new LinkedHashMap<>();

    public Catalog(Path path) throws IOException {
        this.path = path;
        carregar();
    }

    private void carregar() throws IOException {
        if (!Files.exists(path)) return;
        int lineNo = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank()) continue;
            String[] f = line.split(";", -1);
            try {
                if (f.length != 6) throw new IllegalArgumentException();
                List<String> ids = f[4].isEmpty() ? List.of() : List.of(f[4].split(",", -1));
                EntradaCatalogo entry = new EntradaCatalogo(f[0], Long.parseLong(f[1]),
                        Long.parseLong(f[2]), Long.parseLong(f[3]), ids,
                        EntradaCatalogo.Estado.valueOf(f[5]));
                if (entries.putIfAbsent(entry.chunkId(), entry) != null) throw new IllegalArgumentException();
            } catch (RuntimeException e) {
                throw new IOException("Linha inválida em catalogo.txt: " + lineNo, e);
            }
        }
    }

    public synchronized void adicionar(EntradaCatalogo entrada) throws IOException {
        EntradaCatalogo old = entries.putIfAbsent(entrada.chunkId(), entrada);
        if (old != null && (old.lsnDe() != entrada.lsnDe() || old.lsnAte() != entrada.lsnAte()
                || old.tamanho() != entrada.tamanho())) {
            throw new IOException("Metadados contraditórios para chunk " + entrada.chunkId());
        }
        gravarAtomico();
    }

    public synchronized void confirmar(String chunkId, String replicaId, boolean duravel) throws IOException {
        EntradaCatalogo old = entries.get(chunkId);
        if (old == null) throw new IOException("Chunk ausente do catálogo: " + chunkId);
        List<String> replicas = new ArrayList<>(old.replicas());
        if (!replicas.contains(replicaId)) replicas.add(replicaId);
        EntradaCatalogo.Estado estado = duravel ? EntradaCatalogo.Estado.DURAVEL : old.estado();
        entries.put(chunkId, new EntradaCatalogo(chunkId, old.lsnDe(), old.lsnAte(),
                old.tamanho(), replicas, estado));
        gravarAtomico();
    }

    public synchronized List<String> copias(String chunkId) {
        EntradaCatalogo entry = entries.get(chunkId);
        return entry == null ? List.of() : entry.replicas();
    }

    public synchronized EntradaCatalogo obter(String chunkId) { return entries.get(chunkId); }
    public synchronized List<EntradaCatalogo> entradas() { return List.copyOf(entries.values()); }

    public synchronized void gravarAtomico() throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = entries.values().stream().map(EntradaCatalogo::linha)
                .reduce("", (a, b) -> a + b + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw new IOException("Escrita de catálogo sem progresso");
            }
            channel.force(true);
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
