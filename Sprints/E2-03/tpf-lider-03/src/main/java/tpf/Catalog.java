package tpf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class Catalog {

    public enum EstadoChunk {
        SELADO,
        DURAVEL
    }

    public static final class EntradaCatalogo {
        private final String chunkId;
        private final long lsnDe;
        private final long lsnAte;
        private final long tamanho;
        private final Set<String> replicas;
        private EstadoChunk estado;

        public EntradaCatalogo(
                String chunkId,
                long lsnDe,
                long lsnAte,
                long tamanho,
                Set<String> replicas,
                EstadoChunk estado) {
            this.chunkId = Objects.requireNonNull(chunkId, "chunkId obrigatório");
            this.lsnDe = lsnDe;
            this.lsnAte = lsnAte;
            this.tamanho = tamanho;
            this.replicas = new LinkedHashSet<>(replicas != null ? replicas : Collections.emptySet());
            this.estado = Objects.requireNonNull(estado, "estado obrigatório");
        }

        public String getChunkId() {
            return chunkId;
        }

        public long getLsnDe() {
            return lsnDe;
        }

        public long getLsnAte() {
            return lsnAte;
        }

        public long getTamanho() {
            return tamanho;
        }

        public Set<String> getReplicas() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(replicas));
        }

        public EstadoChunk getEstado() {
            return estado;
        }

        public int numeroCopias() {
            return replicas.size();
        }
    }

    public static final class ResumoCatalogo {
        public final int totalChunks;
        public final int totalSelados;
        public final int totalDuraveis;
        public final int totalSubreplicados;
        public final Map<String, Integer> chunksPorReplica;

        public ResumoCatalogo(
                int totalChunks,
                int totalSelados,
                int totalDuraveis,
                int totalSubreplicados,
                Map<String, Integer> chunksPorReplica) {
            this.totalChunks = totalChunks;
            this.totalSelados = totalSelados;
            this.totalDuraveis = totalDuraveis;
            this.totalSubreplicados = totalSubreplicados;
            this.chunksPorReplica = Collections.unmodifiableMap(chunksPorReplica);
        }
    }

    private final Object lock = new Object();
    private final Map<String, EntradaCatalogo> entradas = new LinkedHashMap<>();
    private final Path ficheiroCatalogo;
    private final Consumer<String> logger;

    public Catalog(Path ficheiroCatalogo) throws IOException {
        this(ficheiroCatalogo, System.out::println);
    }

    public Catalog(Path ficheiroCatalogo, Consumer<String> logger) throws IOException {
        this.ficheiroCatalogo = Objects.requireNonNull(ficheiroCatalogo, "ficheiroCatalogo obrigatório");
        this.logger = (logger != null) ? logger : System.out::println;
        carregar();
    }

    private void carregar() throws IOException {
        synchronized (lock) {
            entradas.clear();
            if (Files.notExists(ficheiroCatalogo)) {
                return;
            }
            List<String> linhas = Files.readAllLines(ficheiroCatalogo, StandardCharsets.UTF_8);
            for (String linha : linhas) {
                if (linha == null || linha.isBlank()) {
                    continue;
                }
                // Formato: chunkId;lsnDe;lsnAte;tamanho;r1,r2,...;estado
                String[] partes = linha.split(";", -1);
                if (partes.length != 6) {
                    throw new IOException("Linha inválida em catalogo.txt: " + linha);
                }
                String chunkId = partes[0];
                long lsnDe = Long.parseLong(partes[1]);
                long lsnAte = Long.parseLong(partes[2]);
                long tamanho = Long.parseLong(partes[3]);
                Set<String> reps = new LinkedHashSet<>();
                if (!partes[4].isEmpty()) {
                    for (String r : partes[4].split(",", -1)) {
                        if (!r.isBlank()) {
                            reps.add(r);
                        }
                    }
                }
                EstadoChunk estado = EstadoChunk.valueOf(partes[5]);
                entradas.put(chunkId, new EntradaCatalogo(chunkId, lsnDe, lsnAte, tamanho, reps, estado));
            }
        }
    }

    /**
     * Regista um novo chunk selado no catálogo com estado SELADO e persiste atomicamente.
     */
    public EntradaCatalogo adicionar(String chunkId, long lsnDe, long lsnAte, long tamanho) throws IOException {
        Objects.requireNonNull(chunkId, "chunkId obrigatório");
        synchronized (lock) {
            EntradaCatalogo existente = entradas.get(chunkId);
            if (existente != null) {
                return existente;
            }
            EntradaCatalogo nova = new EntradaCatalogo(
                    chunkId,
                    lsnDe,
                    lsnAte,
                    tamanho,
                    Collections.emptySet(),
                    EstadoChunk.SELADO
            );
            entradas.put(chunkId, nova);
            gravarAtomicoLocked();
            return nova;
        }
    }

    /**
     * Confirma uma réplica detentora do chunk e atualiza o estado para DURAVEL
     * assim que o quórum de durabilidade (Wire.quorumDurabilidade()) for atingido.
     */
    public boolean confirmar(String chunkId, String idReplica) throws IOException {
        Objects.requireNonNull(chunkId, "chunkId obrigatório");
        Objects.requireNonNull(idReplica, "idReplica obrigatório");

        synchronized (lock) {
            EntradaCatalogo entrada = entradas.get(chunkId);
            if (entrada == null) {
                return false;
            }

            boolean adicionada = entrada.replicas.add(idReplica);
            EstadoChunk estadoAnterior = entrada.estado;
            int quorum = Wire.quorumDurabilidade();

            if (entrada.replicas.size() >= quorum && entrada.estado == EstadoChunk.SELADO) {
                entrada.estado = EstadoChunk.DURAVEL;
            }

            if (adicionada || estadoAnterior != entrada.estado) {
                gravarAtomicoLocked();
            }

            if (estadoAnterior != EstadoChunk.DURAVEL && entrada.estado == EstadoChunk.DURAVEL) {
                logger.accept("DURAVEL " + chunkId + " copias=" + entrada.replicas.size() + "/" + Wire.R);
            }

            return adicionada;
        }
    }

    /**
     * Remove uma réplica revogada das entradas do catálogo e regrava atomicamente.
     */
    public void removerReplicaDeTodasEntradas(String idReplica) throws IOException {
        if (idReplica == null || idReplica.isBlank()) {
            return;
        }
        synchronized (lock) {
            boolean alterou = false;
            for (EntradaCatalogo entrada : entradas.values()) {
                if (entrada.replicas.remove(idReplica)) {
                    alterou = true;
                }
            }
            if (alterou) {
                gravarAtomicoLocked();
            }
        }
    }

    public int copias(String chunkId) {
        synchronized (lock) {
            EntradaCatalogo entrada = entradas.get(chunkId);
            return (entrada != null) ? entrada.replicas.size() : 0;
        }
    }

    public Set<String> replicasConfirmadas(String chunkId) {
        synchronized (lock) {
            EntradaCatalogo entrada = entradas.get(chunkId);
            return (entrada != null) ? entrada.getReplicas() : Collections.emptySet();
        }
    }

    public EntradaCatalogo obter(String chunkId) {
        synchronized (lock) {
            EntradaCatalogo e = entradas.get(chunkId);
            if (e == null) {
                return null;
            }
            return new EntradaCatalogo(e.chunkId, e.lsnDe, e.lsnAte, e.tamanho, e.replicas, e.estado);
        }
    }

    public List<EntradaCatalogo> listarTodas() {
        synchronized (lock) {
            List<EntradaCatalogo> copia = new ArrayList<>(entradas.size());
            for (EntradaCatalogo e : entradas.values()) {
                copia.add(new EntradaCatalogo(e.chunkId, e.lsnDe, e.lsnAte, e.tamanho, e.replicas, e.estado));
            }
            return Collections.unmodifiableList(copia);
        }
    }

    public ResumoCatalogo resumo() {
        synchronized (lock) {
            int selados = 0;
            int duraveis = 0;
            int subreplicados = 0;
            Map<String, Integer> contagemPorReplica = new LinkedHashMap<>();

            for (EntradaCatalogo e : entradas.values()) {
                if (e.estado == EstadoChunk.DURAVEL) {
                    duraveis++;
                } else {
                    selados++;
                }
                if (e.replicas.size() < Wire.R) {
                    subreplicados++;
                }
                for (String rep : e.replicas) {
                    contagemPorReplica.merge(rep, 1, Integer::sum);
                }
            }

            return new ResumoCatalogo(
                    entradas.size(),
                    selados,
                    duraveis,
                    subreplicados,
                    contagemPorReplica
            );
        }
    }

    public void gravarAtomico() throws IOException {
        synchronized (lock) {
            gravarAtomicoLocked();
        }
    }

    private void gravarAtomicoLocked() throws IOException {
        Path pai = ficheiroCatalogo.getParent();
        if (pai != null && Files.notExists(pai)) {
            Files.createDirectories(pai);
        }

        List<String> linhas = new ArrayList<>(entradas.size());
        for (EntradaCatalogo e : entradas.values()) {
            String repsCsv = String.join(",", e.replicas);
            linhas.add(e.chunkId + ";"
                    + e.lsnDe + ";"
                    + e.lsnAte + ";"
                    + e.tamanho + ";"
                    + repsCsv + ";"
                    + e.estado.name());
        }

        Path tmp = ficheiroCatalogo.resolveSibling(ficheiroCatalogo.getFileName().toString() + ".tmp");
        Files.write(
                tmp,
                linhas,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        Files.move(tmp, ficheiroCatalogo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
