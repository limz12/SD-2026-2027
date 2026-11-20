package tpf;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class ChunkManager {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private final List<String> registos = new ArrayList<>();
    private final Path pastaSelados, caminhoEventos;
    private final ChunkCrypto crypto;

    private int tamanhoBytes = 4;
    private ScheduledFuture<?> tarefaSelagem;
    private boolean fechado = false;

    private Sealed selagemPendente;
    private List<String> registosPendentes;

    private static final long TEMPO_REPETICAO_MS = 1000;

    // CONSTRUTOR
    public ChunkManager(Path pastaSelados, Path caminhoEventos)
            throws Exception {

        this.pastaSelados = pastaSelados;
        this.caminhoEventos = caminhoEventos;

        Files.createDirectories(pastaSelados);
        Files.createDirectories(caminhoEventos.getParent());

        this.crypto = new ChunkCrypto();

        recuperarChunks();
    }

    // ADICIONAR REGISTO
    public synchronized void adicionarRegistoChunk(String registo) {

       if (fechado){
            throw new IllegalStateException("ChunkManager fechado");
        }
        int tamanho = 4 + registo.getBytes(StandardCharsets.UTF_8).length;

        // Se o chunk atual já tem registos e o novo vai fazer ultrapassar o limite,
        // sela o chunk atual primeiro antes de abrir o novo com este registo
        if (!registos.isEmpty() && tamanhoBytes + tamanho > Wire.CHUNK_MAX_BYTES) {
            selarChunk();
        }

        if (registos.isEmpty() && registosPendentes == null) {
            iniciarTemporizador();
        }

        registos.add(registo);
        tamanhoBytes += tamanho;

        System.out.println("[CHUNK] Registos: " + registos.size()
                + " | Bytes: " + tamanhoBytes);

        // Se atingiu exatamente (ou um único registo gigante excedeu) o limite, sela já
        if (tamanhoBytes >= Wire.CHUNK_MAX_BYTES) {
            selarChunk();
        }
    }

    // INICIAR TEMPORIZADOR
    private void iniciarTemporizador() {

        if (tarefaSelagem != null)
            tarefaSelagem.cancel(false);

        tarefaSelagem = scheduler.schedule(
                this::selarChunk,
                Wire.SELAGEM_MS,
                TimeUnit.MILLISECONDS
        );
    }

    // SELAR CHUNK
    private synchronized void selarChunk() {

        if (registos.isEmpty())
            return;

        if (tarefaSelagem != null) {
            tarefaSelagem.cancel(false);
            tarefaSelagem = null;
        }

        try {
            if (registosPendentes == null) {
                registosPendentes = new ArrayList<>(registos);
                selagemPendente = crypto.seal(
                        serializarChunk(registosPendentes));
            }

            processarChunk(selagemPendente, registosPendentes);

            registos.subList(0, registosPendentes.size()).clear();

            selagemPendente = null;
            registosPendentes = null;

            recalcularTamanho();

            System.out.println("[CHUNK] Selado com sucesso!");

            if (!registos.isEmpty())
                iniciarTemporizador();

        } catch (Exception e) {
            System.err.println("[CHUNK] Erro ao selar:");
            e.printStackTrace();
            agendarRepeticao();
        }
    }

    // REPETIR SELAGEM EM CASO DE ERRO
    private synchronized void agendarRepeticao() {

        if (fechado || scheduler.isShutdown())
            return;

        if (tarefaSelagem != null && !tarefaSelagem.isDone())
            return;

        tarefaSelagem = scheduler.schedule(
                this::selarChunk,
                TEMPO_REPETICAO_MS,
                TimeUnit.MILLISECONDS
        );
    }

    // RECALCULAR TAMANHO
    private void recalcularTamanho() {

        tamanhoBytes = 4;

        for (String r : registos)
            tamanhoBytes += 4 +
                    r.getBytes(StandardCharsets.UTF_8).length;
    }

    // SERIALIZAÇÃO
    private byte[] serializarChunk(List<String> chunk)
            throws IOException {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);

        out.writeInt(chunk.size());

        for (String r : chunk) {
            byte[] dados = r.getBytes(StandardCharsets.UTF_8);
            out.writeInt(dados.length);
            out.write(dados);
        }

        if (Wire.PAD_BYTES <= 0)
            throw new IOException("PAD_BYTES inválido");

        int padding = (Wire.PAD_BYTES -
                buffer.size() % Wire.PAD_BYTES) % Wire.PAD_BYTES;

        for (int i = 0; i < padding; i++)
            out.writeByte(0);

        out.flush();
        return buffer.toByteArray();
    }

    // DESSERIALIZAÇÃO
    public List<String> desserializarChunk(byte[] dados)
            throws IOException {

        if (dados.length < 4)
            throw new IOException("Chunk inválido");

        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(dados));

        int quantidade = in.readInt();

        if (quantidade < 0)
            throw new IOException("Número de registos inválido");

        List<String> resultado = new ArrayList<>();

        for (int i = 0; i < quantidade; i++) {

            if (in.available() < 4)
                throw new IOException("Chunk incompleto");

            int tamanho = in.readInt();

            if (tamanho < 0 || tamanho > in.available())
                throw new IOException("Tamanho inválido");

            resultado.add(new String(
                    in.readNBytes(tamanho),
                    StandardCharsets.UTF_8));
        }

        return resultado;
    }

    // PROCESSAR E GUARDAR CHUNK
    private void processarChunk(Sealed sealed, List<String> chunk)
            throws Exception {

        String id = sealed.getChunkId();

        Path bin = pastaSelados.resolve(id + ".bin");
        Path hdr = pastaSelados.resolve(id + ".hdr");

        Path tmpBin = pastaSelados.resolve(id + ".bin.tmp");
        Path tmpHdr = pastaSelados.resolve(id + ".hdr.tmp");

        if (!(Files.exists(bin) && Files.exists(hdr))) {

            Files.deleteIfExists(bin);
            Files.deleteIfExists(hdr);
            Files.deleteIfExists(tmpBin);
            Files.deleteIfExists(tmpHdr);

            try {
                escreverComFsync(tmpBin, sealed.getCiphertext());
                escreverComFsync(tmpHdr,
                        crypto.gerarHeader(sealed)
                                .getBytes(StandardCharsets.UTF_8));

                moverAtomico(tmpBin, bin);
                moverAtomico(tmpHdr, hdr);

            } finally {
                Files.deleteIfExists(tmpBin);
                Files.deleteIfExists(tmpHdr);
            }
        }

        long inicio = obterLSN(chunk.get(0));
        long fim = obterLSN(chunk.get(chunk.size() - 1));

        if (!eventoExiste(id)) {
            Registo.registarEvento(caminhoEventos, "SELADO",
                    "chunkId=" + id
                    + " lsnInicial=" + inicio
                    + " lsnFinal=" + fim
                    + " tamanho=" + sealed.getCiphertext().length);
        }

        System.out.println("[CHUNK] Guardado: " + id);
    }

    // ESCREVER E FORÇAR FSYNC
    private void escreverComFsync(Path caminho, byte[] dados)
            throws IOException {

        try (FileChannel ch = FileChannel.open(caminho,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {

            ByteBuffer b = ByteBuffer.wrap(dados);
            while (b.hasRemaining())
                ch.write(b);

            ch.force(true);
        }
    }

    // MOVER FICHEIRO
    private void moverAtomico(Path origem, Path destino)
            throws IOException {

        try {
            Files.move(origem, destino, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origem, destino);
        }
    }

    // VERIFICAR EVENTO
    private boolean eventoExiste(String id) throws IOException {

        if (!Files.exists(caminhoEventos))
            return false;

        return Files.readString(caminhoEventos)
                .contains("chunkId=" + id);
    }

    // OBTER LSN
    private long obterLSN(String registo) {
        return Long.parseLong(registo.split(";", 2)[0]);
    }

    // ABRIR CHUNK
    public List<String> abrirChunk(String id) throws Exception {

        Path bin = pastaSelados.resolve(id + ".bin");
        Path hdr = pastaSelados.resolve(id + ".hdr");

        if (!Files.exists(bin) || !Files.exists(hdr))
            throw new IOException("Chunk não encontrado: " + id);

        Sealed sealed = crypto.lerHeader(id,
                Files.readString(hdr),
                Files.readAllBytes(bin));

        return desserializarChunk(crypto.open(sealed, id));
    }

    // RECUPERAR CHUNKS
    private void recuperarChunks() throws Exception {

        System.out.println("[CHUNK] A recuperar chunks...");

        try (Stream<Path> ficheiros = Files.list(pastaSelados)) {

            for (Path p : ficheiros
                    .filter(f -> f.toString().endsWith(".tmp")).toList())
                Files.deleteIfExists(p);
        }

        try (Stream<Path> ficheiros = Files.list(pastaSelados)) {

            for (Path bin : ficheiros
                    .filter(f -> f.toString().endsWith(".bin")).toList()) {

                String nome = bin.getFileName().toString();
                String id = nome.substring(0, nome.length() - 4);

                Path hdr = pastaSelados.resolve(id + ".hdr");

                if (!Files.exists(hdr)) {
                    Files.deleteIfExists(bin);
                    continue;
                }

                List<String> recuperados = abrirChunk(id);

                if (recuperados.isEmpty())
                    throw new IOException("Chunk vazio: " + id);

                if (!eventoExiste(id)) {
                    Registo.registarEvento(caminhoEventos, "SELADO",
                            "chunkId=" + id
                            + " lsnInicial=" + obterLSN(recuperados.get(0))
                            + " lsnFinal=" + obterLSN(
                                    recuperados.get(recuperados.size() - 1))
                            + " tamanho=" + Files.size(bin));

                    System.out.println("[CHUNK] Evento recuperado: " + id);
                }
            }
        }

        System.out.println("[CHUNK] Recuperação concluída.");
    }

    // FECHAR CHUNK MANAGER
    public synchronized void fechar() {

        if (fechado)
            return;

        selarChunk();

        if (!registos.isEmpty()) {
            System.err.println(
                    "[CHUNK] Existem registos por selar. Fecho adiado.");
            return;
        }

        fechado = true;

        if (tarefaSelagem != null)
            tarefaSelagem.cancel(false);

        scheduler.shutdown();
    }
}
