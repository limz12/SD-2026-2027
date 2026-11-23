package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Coordena a escrita durável, a selagem e a recuperação local. */
public final class ArquivoLocal implements AutoCloseable {
    private final RegistoLog log;
    private final ChunkCrypto crypto;
    private final Path selados, eventos;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Chunk aberto = new Chunk();
    private ScheduledFuture<?> prazo;
    private IOException falha;

    private record ChunkRecuperado(String id, List<Registo> registos, int tamanho) { }

    public ArquivoLocal(RegistoLog log, byte[] key, Path dados) throws IOException {
        this.log = log;
        this.crypto = new ChunkCrypto(key);
        this.selados = dados.resolve("selados");
        this.eventos = dados.resolve("eventos-lider.txt");
        Files.createDirectories(selados);
        try {
            recuperar();
        } catch (IOException | RuntimeException e) {
            timer.shutdownNow();
            throw e;
        }
    }

    private void evento(String tipo, String detalhe) throws IOException {
        byte[] linha = (System.currentTimeMillis() + ";" + tipo + ";" + detalhe + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel c = FileChannel.open(eventos, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer b = ByteBuffer.wrap(linha);
            while (b.hasRemaining()) {
                if (c.write(b) <= 0) throw new IOException("Escrita de evento sem progresso");
            }
            c.force(true);
        }
    }

    /** Grava e força o ficheiro temporário antes da publicação. */
    private static void escreverNovo(Path caminho, byte[] bytes) throws IOException {
        try (FileChannel c = FileChannel.open(caminho, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) {
                if (c.write(b) <= 0) throw new IOException("Escrita sem progresso: " + caminho);
            }
            c.force(true);
        }
    }

    private Path ficheiro(String id, String sufixo) { return selados.resolve(id + sufixo); }

    private static String validarId(String id) throws IOException {
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("Nome de chunk inválido: " + id);
        return id;
    }

    /** Retoma uma publicação interrompida, sem substituir ficheiros já existentes. */
    private void concluirPendente(String id) throws IOException {
        Path bin = ficheiro(id, ".bin"), hdr = ficheiro(id, ".hdr");
        Path binTmp = ficheiro(id, ".bin.tmp"), hdrTmp = ficheiro(id, ".hdr.tmp");
        Path binFonte = selecionarFonte(bin, binTmp);
        Path hdrFonte = selecionarFonte(hdr, hdrTmp);
        // Autenticar as duas partes antes de publicar qualquer parte pendente.
        abrirPar(id, binFonte, hdrFonte);
        if (binFonte.equals(binTmp)) Files.move(binTmp, bin, StandardCopyOption.ATOMIC_MOVE);
        if (hdrFonte.equals(hdrTmp)) Files.move(hdrTmp, hdr, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path selecionarFonte(Path definitivo, Path temporario) throws IOException {
        boolean temDefinitivo = Files.isRegularFile(definitivo, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        boolean temTemporario = Files.isRegularFile(temporario, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if ((Files.exists(definitivo, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !temDefinitivo)
                || (Files.exists(temporario, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !temTemporario))
            throw new IOException("Caminho de chunk não é ficheiro regular; ficheiros preservados");
        if (temDefinitivo == temTemporario)
            throw new IOException("Publicação de chunk ambígua ou incompleta; ficheiros preservados: " + definitivo);
        return temDefinitivo ? definitivo : temporario;
    }

    private ChunkRecuperado abrirPar(String id, Path bin, Path hdr) throws IOException {
        String[] campos = Files.readString(hdr, StandardCharsets.UTF_8).strip().split(";", -1);
        if (campos.length != 3) throw new IOException("Cabeçalho inválido: " + hdr);
        try {
            byte[] cifrado = Files.readAllBytes(bin);
            Sealed sealed = new Sealed(id, Integer.parseInt(campos[0]), Base64.getDecoder().decode(campos[1]),
                    Base64.getDecoder().decode(campos[2]), cifrado);
            return new ChunkRecuperado(id, Chunk.abrir(crypto.open(sealed)), cifrado.length);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("Não foi possível autenticar o chunk " + id, e);
        }
    }

    private Map<String, String> eventosSelados() throws IOException {
        Map<String, String> resultado = new HashMap<>();
        if (!Files.exists(eventos)) return resultado;
        byte[] bytes = Files.readAllBytes(eventos);
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n')
            throw new IOException("eventos-lider.txt não termina em LF; ficheiro preservado");
        String conteudo = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        for (String linha : conteudo.split("\n")) {
            if (linha.isEmpty()) continue;
            String[] campos = linha.split(";", 3);
            if (campos.length != 3) throw new IOException("Linha inválida em eventos-lider.txt");
            if (campos[1].equals("SELADO")) {
                String detalhe = campos[2];
                if (!detalhe.startsWith("chunkId=") || detalhe.length() < 72)
                    throw new IOException("Evento SELADO inválido");
                String id = validarId(detalhe.substring(8, 72));
                if (resultado.putIfAbsent(id, detalhe) != null) throw new IOException("Evento SELADO duplicado: " + id);
            }
        }
        return resultado;
    }

    private Set<Long> eventosRegistados() throws IOException {
        Set<Long> lsns = new HashSet<>();
        if (!Files.exists(eventos)) return lsns;
        for (String linha : Files.readAllLines(eventos, StandardCharsets.UTF_8)) {
            String[] campos = linha.split(";", 3);
            if (campos.length != 3) throw new IOException("Linha inválida em eventos-lider.txt");
            if (campos[1].equals("REGISTO")) {
                try {
                    if (!campos[2].startsWith("lsn=")) throw new NumberFormatException();
                    long lsn = Long.parseLong(campos[2].substring(4));
                    if (lsn <= 0 || !lsns.add(lsn)) throw new IOException("Evento REGISTO duplicado ou inválido: " + lsn);
                } catch (NumberFormatException e) { throw new IOException("Evento REGISTO inválido", e); }
            }
        }
        return lsns;
    }

    private static String detalhe(ChunkRecuperado chunk) {
        List<Registo> rs = chunk.registos();
        return "chunkId=" + chunk.id() + ";lsn=" + rs.get(0).getLsn() + ".."
                + rs.get(rs.size() - 1).getLsn() + ";tamanho=" + chunk.tamanho();
    }

    /** Valida chunks, reconcilia eventos e sela o sufixo ainda não selado do log. */
    private void recuperar() throws IOException {
        List<Registo> logRegistos = log.lerTodos();
        Map<String, String> eventosExistentes = eventosSelados();
        Set<String> pendentes = new HashSet<>(), bins = new HashSet<>(), hdrs = new HashSet<>();
        try (var paths = Files.list(selados)) {
            for (Path p : paths.toList()) {
                String nome = p.getFileName().toString();
                if (nome.endsWith(".pending")) pendentes.add(validarId(nome.substring(0, nome.length() - 8)));
            }
        }
        for (String id : pendentes) concluirPendente(id);
        try (var paths = Files.list(selados)) {
            for (Path p : paths.toList()) {
                String nome = p.getFileName().toString();
                if (nome.endsWith(".bin")) bins.add(validarId(nome.substring(0, nome.length() - 4)));
                if (nome.endsWith(".hdr")) hdrs.add(validarId(nome.substring(0, nome.length() - 4)));
            }
        }
        if (!bins.equals(hdrs)) throw new IOException("Par .bin/.hdr incompleto; ficheiros preservados em " + selados);
        List<ChunkRecuperado> chunks = new ArrayList<>();
        for (String id : bins) chunks.add(abrirPar(id, ficheiro(id, ".bin"), ficheiro(id, ".hdr")));
        chunks.sort(Comparator.comparingLong(c -> c.registos().get(0).getLsn()));
        int indice = 0;
        for (ChunkRecuperado chunk : chunks) {
            for (Registo r : chunk.registos()) {
                if (indice >= logRegistos.size() || !r.toLine().equals(logRegistos.get(indice).toLine()))
                    throw new IOException("Chunk " + chunk.id() + " não corresponde à sequência de registos.log");
                indice++;
            }
            String antigo = eventosExistentes.remove(chunk.id());
            if (antigo != null && !antigo.equals(detalhe(chunk)))
                throw new IOException("Evento SELADO não corresponde ao chunk " + chunk.id());
        }
        if (!eventosExistentes.isEmpty()) throw new IOException("Evento SELADO referencia chunk inexistente");
        Set<Long> registados = eventosRegistados();
        for (long lsn : registados) {
            if (lsn > logRegistos.size()) throw new IOException("Evento REGISTO sem entrada no log: " + lsn);
        }
        for (Registo r : logRegistos) {
            if (!registados.contains(r.getLsn())) evento("REGISTO", "lsn=" + r.getLsn());
        }
        // Só produzir eventos depois de validar todos os pares e a sequência.
        Map<String, String> eventosVerificados = eventosSelados();
        for (ChunkRecuperado chunk : chunks) {
            if (!eventosVerificados.containsKey(chunk.id())) evento("SELADO", detalhe(chunk));
            if (pendentes.contains(chunk.id())) Files.delete(ficheiro(chunk.id(), ".pending"));
        }
        while (indice < logRegistos.size()) {
            aberto.adicionar(logRegistos.get(indice++));
            if (aberto.tamanho() >= Wire.CHUNK_MAX_BYTES) selar();
        }
        // No reinício sela imediatamente os registos recuperados, sem voltar a escrevê-los no log.
        if (!aberto.vazio()) selar();
    }

    public synchronized Registo registar(String autor, String canal, String texto) throws IOException {
        if (falha != null) throw new IOException("Arquivo em falha", falha);
        Registo r = log.registar(autor, canal, texto);
        try {
            evento("REGISTO", "lsn=" + r.getLsn());
            boolean primeiro = aberto.vazio();
            aberto.adicionar(r);
            if (primeiro) {
                Chunk atual = aberto;
                prazo = timer.schedule(() -> selarPorTempo(atual), Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
            }
            if (aberto.tamanho() >= Wire.CHUNK_MAX_BYTES) selar();
            return r;
        } catch (IOException | RuntimeException e) {
            falha = new IOException("Registo persistido, mas falhou o processamento posterior; LSN=" + r.getLsn(), e);
            throw falha;
        }
    }

    private synchronized void selarPorTempo(Chunk esperado) {
        if (aberto != esperado || aberto.vazio() || falha != null) return;
        try { selar(); } catch (IOException e) { falha = e; System.err.println("Falha ao selar chunk: " + e.getMessage()); }
    }

    private void selar() throws IOException {
        if (aberto.vazio()) return;
        Chunk atual = aberto;
        long de = atual.primeiroLsn(), ate = atual.ultimoLsn();
        try {
            Sealed sealed = crypto.seal(atual.selar(Wire.PAD_BYTES));
            String id = sealed.id();
            byte[] bin = sealed.ciphertext();
            String hdr = sealed.keyVersion() + ";" + Base64.getEncoder().encodeToString(sealed.salt())
                    + ";" + Base64.getEncoder().encodeToString(sealed.nonce()) + "\n";
            escreverNovo(ficheiro(id, ".bin.tmp"), bin);
            escreverNovo(ficheiro(id, ".hdr.tmp"), hdr.getBytes(StandardCharsets.UTF_8));
            escreverNovo(ficheiro(id, ".pending"), new byte[0]);
            concluirPendente(id);
            evento("SELADO", "chunkId=" + id + ";lsn=" + de + ".." + ate + ";tamanho=" + bin.length);
            Files.delete(ficheiro(id, ".pending"));
            if (prazo != null) prazo.cancel(false);
            aberto = new Chunk();
            prazo = null;
        } catch (GeneralSecurityException e) { throw new IOException("Falha criptográfica", e); }
    }

    @Override public synchronized void close() throws IOException {
        timer.shutdownNow();
        if (falha != null) throw new IOException("Arquivo terminou em falha", falha);
        selar();
    }
}
