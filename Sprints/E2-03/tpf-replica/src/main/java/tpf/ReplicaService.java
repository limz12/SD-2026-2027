package tpf;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ReplicaService {

    public enum EstadoSessao {
        DESLIGADA,
        CONECTANDO,
        AGUARDANDO_DESAFIO,
        AUTENTICADA
    }

    private static final String FICHEIRO_CHAVE_PRIVADA = "replica.key";
    private static final String FICHEIRO_CHAVE_PUBLICA = "replica.pub";
    private static final Pattern PADRAO_SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final long TIMEOUT_ENVIO_MS = 5000L;

    private final String id;
    private final URI urlLider;
    private final long quotaBytes;
    private final String tokenInicial;
    private final Path pastaDados;
    private final Path ficheiroEventos;

    private final Object lockArmazenamento = new Object();
    private final Object lockSessao = new Object();
    private final Object lockEventos = new Object();
    private final CountDownLatch encerramentoLatch = new CountDownLatch(1);

    private PrivateKey chavePrivada;
    private PublicKey chavePublica;
    private String chavePublicaX509B64;
    private boolean primeiroRegisto;
    private long bytesUsados;
    private final List<String> chunksLocais = new ArrayList<>();

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tarefaHeartbeat;
    private ScheduledFuture<?> tarefaReconexao;

    private volatile boolean ativo = false;
    private volatile EstadoSessao estadoSessao = EstadoSessao.DESLIGADA;
    private volatile WebSocket webSocketAtual;
    private long backoffAtualMs = Wire.BACKOFF_MIN_MS;

    public ReplicaService(String id, URI urlLider, long quotaBytes, String tokenInicial) {
        this.id = Objects.requireNonNull(id, "id obrigatório");
        this.urlLider = Objects.requireNonNull(urlLider, "urlLider obrigatória");
        if (quotaBytes <= 0L) {
            throw new IllegalArgumentException("quotaBytes deve ser > 0");
        }
        this.quotaBytes = quotaBytes;
        this.tokenInicial = tokenInicial;
        this.pastaDados = Path.of("dados-" + id);
        this.ficheiroEventos = this.pastaDados.resolve("eventos-" + id + ".txt");
        this.httpClient = HttpClient.newHttpClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replica-scheduler-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    // =========================================================================
    // Armazenamento e Chaves Ed25519
    // =========================================================================

    public void inicializarArmazenamentoEChaves() throws IOException, GeneralSecurityException {
        Files.createDirectories(pastaDados);

        Path caminhoPrivada = pastaDados.resolve(FICHEIRO_CHAVE_PRIVADA);
        Path caminhoPublica = pastaDados.resolve(FICHEIRO_CHAVE_PUBLICA);

        boolean existePrivada = Files.exists(caminhoPrivada, LinkOption.NOFOLLOW_LINKS);
        boolean existePublica = Files.exists(caminhoPublica, LinkOption.NOFOLLOW_LINKS);

        if (existePrivada != existePublica) {
            throw new IOException(
                    "Par de chaves incompleto em " + pastaDados + " ("
                            + FICHEIRO_CHAVE_PRIVADA + "=" + existePrivada + ", "
                            + FICHEIRO_CHAVE_PUBLICA + "=" + existePublica + "); arranque recusado.");
        }

        if (existePrivada) {
            carregarChavesExistentes(caminhoPrivada, caminhoPublica);
            this.primeiroRegisto = false;
            if (tokenInicial != null) {
                System.out.println("Aviso: chaves já existentes carregadas de " + pastaDados
                        + "; o [token] passado na linha de comandos será ignorado.");
            }
        } else {
            if (tokenInicial == null) {
                throw new IllegalArgumentException(
                        "Não existem chaves em " + pastaDados
                                + " e não foi fornecido [token] para o 1.º registo.");
            }
            gerarEPersistirChaves(caminhoPrivada, caminhoPublica);
            this.primeiroRegisto = true;
        }

        this.chavePublicaX509B64 = Base64.getEncoder().encodeToString(this.chavePublica.getEncoded());
        limparTemporariosECalcularUsados();
    }

    private void carregarChavesExistentes(Path caminhoPrivada, Path caminhoPublica)
            throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(caminhoPrivada, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(caminhoPublica, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Os ficheiros de chaves em " + pastaDados + " devem ser ficheiros regulares.");
        }

        byte[] pkcs8Bytes = Files.readAllBytes(caminhoPrivada);
        byte[] x509Bytes = Files.readAllBytes(caminhoPublica);

        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(x509Bytes));

        validarCorrespondenciaPar(priv, pub);
        aplicarPermissoesSoDono(caminhoPrivada);

        this.chavePrivada = priv;
        this.chavePublica = pub;
    }

    private void gerarEPersistirChaves(Path caminhoPrivada, Path caminhoPublica)
            throws IOException, GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair par = kpg.generateKeyPair();

        byte[] x509Bytes = par.getPublic().getEncoded();
        byte[] pkcs8Bytes = par.getPrivate().getEncoded();

        Path tmpPub = pastaDados.resolve(FICHEIRO_CHAVE_PUBLICA + ".tmp");
        Files.write(tmpPub, x509Bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(tmpPub, caminhoPublica, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        Path tmpKey = pastaDados.resolve(FICHEIRO_CHAVE_PRIVADA + ".tmp");
        Files.deleteIfExists(tmpKey);
        criarFicheiroComPermissoesDono(tmpKey);
        Files.write(tmpKey, pkcs8Bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(tmpKey, caminhoPrivada, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        aplicarPermissoesSoDono(caminhoPrivada);

        this.chavePrivada = par.getPrivate();
        this.chavePublica = par.getPublic();
    }

    private void validarCorrespondenciaPar(PrivateKey priv, PublicKey pub) throws GeneralSecurityException {
        byte[] desafioTeste = "tpf-verificacao-par-ed25519".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(priv);
        signer.update(desafioTeste);
        byte[] assinatura = signer.sign();

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pub);
        verifier.update(desafioTeste);
        if (!verifier.verify(assinatura)) {
            throw new GeneralSecurityException("A chave pública e a chave privada em " + pastaDados + " não correspondem.");
        }
    }

    private static void criarFicheiroComPermissoesDono(Path ficheiro) throws IOException {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
            Files.createFile(ficheiro, attr);
        } catch (UnsupportedOperationException e) {
            Files.createFile(ficheiro);
            aplicarPermissoesSoDono(ficheiro);
        }
    }

    private static void aplicarPermissoesSoDono(Path ficheiro) throws IOException {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(ficheiro, perms);
            return;
        } catch (UnsupportedOperationException ignored) {
            // Fallback para sistemas não-POSIX
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(ficheiro, AclFileAttributeView.class);
        if (aclView != null) {
            UserPrincipal dono = Files.getOwner(ficheiro);
            AclEntry entryDono = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(dono)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            aclView.setAcl(Collections.singletonList(entryDono));
            return;
        }

        File f = ficheiro.toFile();
        if (!f.setReadable(false, false) || !f.setReadable(true, true)
                || !f.setWritable(false, false) || !f.setWritable(true, true)) {
            throw new IOException("Não foi possível restringir permissões de " + ficheiro + " apenas ao dono.");
        }
    }

    private void limparTemporariosECalcularUsados() throws IOException {
        synchronized (lockArmazenamento) {
            long totalBin = 0L;
            chunksLocais.clear();

            try (Stream<Path> entradas = Files.list(pastaDados)) {
                for (Path p : entradas.toList()) {
                    String nome = p.getFileName().toString();
                    if (nome.endsWith(".tmp")) {
                        Files.deleteIfExists(p);
                        continue;
                    }
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (nome.endsWith(".bin")) {
                        String chunkId = nome.substring(0, nome.length() - ".bin".length());
                        Path hdrPar = pastaDados.resolve(chunkId + ".hdr");
                        if (!chunkId.isBlank() && Files.isRegularFile(hdrPar, LinkOption.NOFOLLOW_LINKS)) {
                            totalBin += Files.size(p);
                            chunksLocais.add(chunkId);
                        } else {
                            // Chunk incompleto (.bin sem .hdr): remove para manter consistência
                            Files.deleteIfExists(p);
                        }
                    } else if (nome.endsWith(".hdr")) {
                        String chunkId = nome.substring(0, nome.length() - ".hdr".length());
                        Path binPar = pastaDados.resolve(chunkId + ".bin");
                        if (chunkId.isBlank() || !Files.isRegularFile(binPar, LinkOption.NOFOLLOW_LINKS)) {
                            // Cabeçalho órfão (.hdr sem .bin): remove
                            Files.deleteIfExists(p);
                        }
                    }
                }
            }

            Collections.sort(chunksLocais);
            this.bytesUsados = totalBin;
        }
    }

    // =========================================================================
    // Ligação WebSocket, Handshake, Heartbeat e Reconexão
    // =========================================================================

    public void iniciar() {
        this.ativo = true;
        conectar();
    }

    public void conectar() {
        synchronized (lockSessao) {
            if (!ativo) {
                return;
            }
            if (estadoSessao == EstadoSessao.CONECTANDO
                    || estadoSessao == EstadoSessao.AGUARDANDO_DESAFIO
                    || estadoSessao == EstadoSessao.AUTENTICADA) {
                return;
            }
            this.estadoSessao = EstadoSessao.CONECTANDO;
        }

        httpClient.newWebSocketBuilder()
                .buildAsync(urlLider, new SessaoWebSocketListener())
                .whenComplete((ws, erro) -> {
                    if (erro != null) {
                        String motivo = causaReal(erro).getMessage();
                        registarEvento("ERRO", "Falha ao ligar a " + urlLider + ": " + motivo);
                        tratarQuedaEAgendarReconexao(null, "Falha de ligação: " + motivo);
                    }
                });
    }

    public void aguardarEncerramento() throws InterruptedException {
        encerramentoLatch.await();
    }

    public void parar() {
        WebSocket wsParaFechar;
        synchronized (lockSessao) {
            if (!ativo && estadoSessao == EstadoSessao.DESLIGADA) {
                encerramentoLatch.countDown();
                return;
            }
            this.ativo = false;
            this.estadoSessao = EstadoSessao.DESLIGADA;
            cancelarHeartbeatLocked();
            if (tarefaReconexao != null) {
                tarefaReconexao.cancel(false);
                tarefaReconexao = null;
            }
            wsParaFechar = this.webSocketAtual;
            this.webSocketAtual = null;
        }

        if (wsParaFechar != null) {
            try {
                wsParaFechar.sendClose(WebSocket.NORMAL_CLOSURE, "Encerramento da réplica")
                        .get(2000L, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                wsParaFechar.abort();
            }
        }
        scheduler.shutdownNow();
        encerramentoLatch.countDown();
    }

    private final class SessaoWebSocketListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (lockSessao) {
                if (!ativo) {
                    webSocket.abort();
                    return;
                }
                webSocketAtual = webSocket;
                estadoSessao = EstadoSessao.AGUARDANDO_DESAFIO;

                String frameOla = primeiroRegisto
                        ? "OLA;" + id + ";" + chavePublicaX509B64 + ";" + tokenInicial
                        : "OLA;" + id;

                if (!enviarSequencial(webSocket, frameOla)) {
                    webSocket.abort();
                    tratarQuedaEAgendarReconexao(webSocket, "Falha ao enviar OLA");
                    return;
                }
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }

            String mensagemCompleta = buffer.toString();
            buffer.setLength(0);

            processarMensagemRecebida(webSocket, mensagemCompleta);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            registarEvento("ERRO", "Frame binário não suportado recebido do servidor");
            webSocket.abort();
            tratarQuedaEAgendarReconexao(webSocket, "Frame binário recebido");
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            String motivo = "code=" + statusCode + (reason != null && !reason.isBlank() ? " reason=" + reason : "");
            tratarQuedaEAgendarReconexao(webSocket, motivo);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            String motivo = causaReal(error).getMessage();
            registarEvento("ERRO", "Erro WebSocket: " + motivo);
            webSocket.abort();
            tratarQuedaEAgendarReconexao(webSocket, "Erro WebSocket: " + motivo);
        }
    }

    private void tratarQuedaEAgendarReconexao(WebSocket socketOrigem, String motivo) {
        long atrasoAgendadoMs = -1L;

        synchronized (lockSessao) {
            if (socketOrigem != null && webSocketAtual != null && webSocketAtual != socketOrigem) {
                return;
            }
            if (socketOrigem == null && estadoSessao == EstadoSessao.DESLIGADA) {
                return;
            }

            boolean estavaConectadaOuAutenticada = (estadoSessao == EstadoSessao.AGUARDANDO_DESAFIO
                    || estadoSessao == EstadoSessao.AUTENTICADA);

            webSocketAtual = null;
            estadoSessao = EstadoSessao.DESLIGADA;
            cancelarHeartbeatLocked();

            if (estavaConectadaOuAutenticada) {
                registarEvento("DESLIGADO", motivo);
            }

            if (!ativo) {
                return;
            }

            if (tarefaReconexao == null || tarefaReconexao.isDone()) {
                atrasoAgendadoMs = backoffAtualMs;
                backoffAtualMs = Math.min(Wire.BACKOFF_MAX_MS, backoffAtualMs * 2L);

                registarEvento("RELIGAR", String.valueOf(atrasoAgendadoMs));
                tarefaReconexao = scheduler.schedule(
                        this::conectar,
                        atrasoAgendadoMs,
                        TimeUnit.MILLISECONDS
                );
            }
        }
    }

    private void iniciarHeartbeatLocked(WebSocket webSocket) {
        cancelarHeartbeatLocked();
        tarefaHeartbeat = scheduler.scheduleAtFixedRate(
                () -> enviarHeartbeat(webSocket),
                0L,
                Wire.PERIODO_HB_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void cancelarHeartbeatLocked() {
        if (tarefaHeartbeat != null) {
            tarefaHeartbeat.cancel(false);
            tarefaHeartbeat = null;
        }
    }

    private void enviarHeartbeat(WebSocket webSocketEsperado) {
        long usadosAtuais = getBytesUsados();
        String frameHb = "PUB;tpf-lider;HB;" + id + ";" + usadosAtuais + ";" + quotaBytes;

        synchronized (lockSessao) {
            if (!ativo
                    || webSocketAtual != webSocketEsperado
                    || estadoSessao != EstadoSessao.AUTENTICADA) {
                return;
            }
            if (!enviarSequencial(webSocketEsperado, frameHb)) {
                webSocketEsperado.abort();
                tratarQuedaEAgendarReconexao(webSocketEsperado, "Falha ao enviar heartbeat");
            }
        }
    }

    private void processarMensagemRecebida(WebSocket webSocket, String mensagem) {
        if (mensagem == null || mensagem.isEmpty()) {
            return;
        }

        EstadoSessao faseAtual = this.estadoSessao;
        if (faseAtual == EstadoSessao.AGUARDANDO_DESAFIO) {
            tratarDesafioEConcluirHandshake(webSocket, mensagem);
        } else if (faseAtual == EstadoSessao.AUTENTICADA) {
            if (mensagem.startsWith("STORE;")) {
                tratarStoreChunk(webSocket, mensagem);
            } else {
                registarEvento("ERRO", "Mensagem não suportada em estado AUTENTICADA: "
                        + mensagem.substring(0, Math.min(64, mensagem.length())));
            }
        } else {
            registarEvento("ERRO", "Mensagem inesperada no estado " + faseAtual);
        }
    }

    private void tratarDesafioEConcluirHandshake(WebSocket webSocket, String mensagem) {
        String[] partes = mensagem.split(";", -1);
        if (partes.length != 2 || !"DESAFIO".equals(partes[0]) || partes[1].isBlank()) {
            registarEvento("ERRO", "Frame inválido ao aguardar DESAFIO");
            fecharSessaoPorErro(webSocket, "Frame inválido ao aguardar DESAFIO");
            return;
        }

        String nonceB64 = partes[1];
        String assinaturaB64;
        try {
            assinaturaB64 = assinarDesafio(nonceB64);
        } catch (GeneralSecurityException e) {
            registarEvento("ERRO", "Falha criptográfica ao assinar desafio: " + e.getMessage());
            fecharSessaoPorErro(webSocket, "Erro ao assinar desafio");
            return;
        }

        synchronized (lockSessao) {
            if (webSocketAtual != webSocket || estadoSessao != EstadoSessao.AGUARDANDO_DESAFIO) {
                return;
            }

            String frameProva = "PROVA;" + id + ";" + assinaturaB64;
            if (!enviarSequencial(webSocket, frameProva)) {
                fecharSessaoPorErro(webSocket, "Falha ao enviar PROVA");
                return;
            }

            this.primeiroRegisto = false;
            this.estadoSessao = EstadoSessao.AUTENTICADA;
            this.backoffAtualMs = Wire.BACKOFF_MIN_MS;

            if (!enviarSequencial(webSocket, "SUB;tpf-r-" + id)
                    || !enviarSequencial(webSocket, "SUB;tpf-ctl")) {
                fecharSessaoPorErro(webSocket, "Falha ao subscrever tópicos");
                return;
            }

            String listaChunks = obterInventarioCsv();
            String frameInventario = "PUB;tpf-lider;INVENTARIO;" + id + ";" + listaChunks;
            if (!enviarSequencial(webSocket, frameInventario)) {
                fecharSessaoPorErro(webSocket, "Falha ao publicar INVENTARIO");
                return;
            }

            registarEvento("LIGADO", urlLider.toString());
            iniciarHeartbeatLocked(webSocket);
        }
    }

    // =========================================================================
    // Etapa 5: STORE, SHA-256, Quota, Escrita Atómica e ACK
    // =========================================================================

    private void tratarStoreChunk(WebSocket webSocket, String mensagem) {
        // Formato esperado: STORE;<chunkId>;<hdrB64>;<binB64>
        String[] partes = mensagem.split(";", 4);
        if (partes.length != 4) {
            registarEvento("ERRO", "STORE malformado (campos=" + partes.length + ")");
            return;
        }

        String chunkId = partes[1];
        String hdrB64 = partes[2];
        String binB64 = partes[3];

        if (!PADRAO_SHA256_HEX.matcher(chunkId).matches() || hdrB64.isEmpty() || binB64.isEmpty()) {
            registarEvento("ERRO", "STORE com chunkId inválido ou payloads vazios");
            return;
        }

        byte[] hdrBytes;
        byte[] binBytes;
        try {
            hdrBytes = Base64.getDecoder().decode(hdrB64);
            binBytes = Base64.getDecoder().decode(binB64);
        } catch (IllegalArgumentException e) {
            registarEvento("ERRO", "STORE " + chunkId + " com Base64 inválido");
            return;
        }

        String sha256Calculado;
        try {
            sha256Calculado = calcularSha256Hex(binBytes);
        } catch (GeneralSecurityException e) {
            registarEvento("ERRO", "SHA-256 indisponível: " + e.getMessage());
            return;
        }

        if (!chunkId.equals(sha256Calculado)) {
            registarEvento("ERRO",
                    "STORE rejeitado: hash divergente esperado=" + chunkId + " calculado=" + sha256Calculado);
            return;
        }

        Path destinoHdr = pastaDados.resolve(chunkId + ".hdr");
        Path destinoBin = pastaDados.resolve(chunkId + ".bin");

        boolean enviarAck = false;

        synchronized (lockArmazenamento) {
            boolean existeHdr = Files.isRegularFile(destinoHdr, LinkOption.NOFOLLOW_LINKS);
            boolean existeBin = Files.isRegularFile(destinoBin, LinkOption.NOFOLLOW_LINKS);

            if (existeHdr && existeBin) {
                // Idempotência: ambos existem -> responde com ACK sem reescrever nem somar quota
                enviarAck = true;
            } else {
                // Caso anómalo de ficheiro único parcial -> limpa antes de regravar
                try {
                    if (existeBin) {
                        long tamOrfao = Files.size(destinoBin);
                        Files.deleteIfExists(destinoBin);
                        bytesUsados = Math.max(0L, bytesUsados - tamOrfao);
                        chunksLocais.remove(chunkId);
                    }
                    if (existeHdr) {
                        Files.deleteIfExists(destinoHdr);
                    }
                } catch (IOException e) {
                    registarEvento("ERRO", "Falha ao limpar ficheiro parcial de " + chunkId + ": " + e.getMessage());
                    return;
                }

                long tamanhoNovo = binBytes.length;
                if (bytesUsados + tamanhoNovo > quotaBytes) {
                    registarEvento("QUOTA",
                            chunkId + " usados=" + bytesUsados + " tamanho=" + tamanhoNovo + " quota=" + quotaBytes);
                    return;
                }

                Path tmpHdr = pastaDados.resolve(chunkId + ".hdr.tmp");
                Path tmpBin = pastaDados.resolve(chunkId + ".bin.tmp");

                try {
                    // 1. Gravar .hdr atomicamente primeiro
                    Files.write(tmpHdr, hdrBytes,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    Files.move(tmpHdr, destinoHdr,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                    // 2. Gravar .bin atomicamente por último (marca conclusão)
                    Files.write(tmpBin, binBytes,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    Files.move(tmpBin, destinoBin,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                    bytesUsados += tamanhoNovo;
                    if (!chunksLocais.contains(chunkId)) {
                        chunksLocais.add(chunkId);
                        Collections.sort(chunksLocais);
                    }

                    registarEvento("STORE", chunkId + " tamanho=" + tamanhoNovo + " usados=" + bytesUsados);
                    enviarAck = true;

                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(tmpHdr);
                        Files.deleteIfExists(tmpBin);
                    } catch (IOException ignored) {
                    }
                    registarEvento("ERRO", "Falha ao gravar chunk " + chunkId + ": " + e.getMessage());
                    return;
                }
            }
        }

        if (enviarAck) {
            String frameAck = "PUB;tpf-lider;ACK;" + id + ";" + chunkId + ";" + sha256Calculado;
            synchronized (lockSessao) {
                if (ativo && webSocketAtual == webSocket && estadoSessao == EstadoSessao.AUTENTICADA) {
                    if (!enviarSequencial(webSocket, frameAck)) {
                        webSocket.abort();
                        tratarQuedaEAgendarReconexao(webSocket, "Falha ao enviar ACK de " + chunkId);
                    }
                }
            }
        }
    }

    private static String calcularSha256Hex(byte[] dados) throws GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(dados);
        return HexFormat.of().formatHex(digest);
    }

    private String assinarDesafio(String nonceB64) throws GeneralSecurityException {
        String payloadAssinar = "tpf-registo|" + id + "|" + nonceB64;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(chavePrivada);
        signer.update(payloadAssinar.getBytes(StandardCharsets.UTF_8));
        byte[] assinatura = signer.sign();
        return Base64.getEncoder().encodeToString(assinatura);
    }

    private boolean enviarSequencial(WebSocket webSocket, String frame) {
        try {
            webSocket.sendText(frame, true).get(TIMEOUT_ENVIO_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            registarEvento("ERRO", "Envio WebSocket interrompido");
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            registarEvento("ERRO", "Falha ao enviar frame WebSocket: " + causaReal(e).getMessage());
            return false;
        }
    }

    private void fecharSessaoPorErro(WebSocket webSocket, String motivo) {
        if (webSocket != null) {
            webSocket.abort();
        }
        tratarQuedaEAgendarReconexao(webSocket, motivo);
    }

    private String obterInventarioCsv() {
        synchronized (lockArmazenamento) {
            return String.join(",", chunksLocais);
        }
    }

    private void registarEvento(String tipo, String detalhe) {
        String linha = Instant.now() + " " + tipo + (detalhe != null && !detalhe.isBlank() ? " " + detalhe : "");
        System.out.println("[" + id + "] " + linha);
        synchronized (lockEventos) {
            try {
                Files.createDirectories(pastaDados);
                Files.writeString(
                        ficheiroEventos,
                        linha + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                System.err.println("ERRO ao escrever em " + ficheiroEventos + ": " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Getters e Validação CLI
    // =========================================================================

    public String getId() {
        return id;
    }

    public URI getUrlLider() {
        return urlLider;
    }

    public long getQuotaBytes() {
        return quotaBytes;
    }

    public Path getPastaDados() {
        return pastaDados;
    }

    public long getBytesUsados() {
        synchronized (lockArmazenamento) {
            return bytesUsados;
        }
    }

    public EstadoSessao getEstadoSessao() {
        return estadoSessao;
    }

    private static Throwable causaReal(Throwable erro) {
        Throwable atual = erro;
        while (atual != null && atual.getCause() != null
                && (atual instanceof ExecutionException || atual instanceof CompletionException)) {
            atual = atual.getCause();
        }
        return (atual != null) ? atual : erro;
    }

    private static void validarWire() {
        if (Wire.PERIODO_HB_MS <= 0L) {
            throw new IllegalArgumentException("tpf.periodoHbMs deve ser > 0");
        }
        if (Wire.BACKOFF_MIN_MS > Wire.BACKOFF_MAX_MS) {
            throw new IllegalArgumentException(
                    "Propriedade JVM inválida: tpf.backoffMinMs=" + Wire.BACKOFF_MIN_MS
                            + " deve ser <= tpf.backoffMaxMs=" + Wire.BACKOFF_MAX_MS);
        }
    }

    private static ReplicaService criarDeArgumentos(String[] args) {
        if (args == null || (args.length != 3 && args.length != 4)) {
            throw new IllegalArgumentException(
                    "Utilização: ReplicaService <id> <url> <quotaBytes> [token]");
        }

        String id = args[0].trim();
        if (id.isEmpty()
                || id.contains(";")
                || id.contains(",")
                || id.contains("/")
                || id.contains("\\")
                || id.contains("..")
                || id.contains("\n")
                || id.contains("\r")) {
            throw new IllegalArgumentException("Argumento <id> inválido: '" + args[0] + "'");
        }

        URI uri;
        try {
            uri = new URI(args[1].trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Argumento <url> inválido: '" + args[1] + "'", e);
        }
        String esquema = uri.getScheme();
        if ((!"ws".equalsIgnoreCase(esquema) && !"wss".equalsIgnoreCase(esquema))
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "Argumento <url> deve usar esquema ws:// ou wss:// com anfitrião válido: '" + args[1] + "'");
        }

        long quotaBytes;
        try {
            quotaBytes = Long.parseLong(args[2].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Argumento <quotaBytes> inválido (esperado inteiro longo positivo): '" + args[2] + "'", e);
        }
        if (quotaBytes <= 0L) {
            throw new IllegalArgumentException(
                    "Argumento <quotaBytes> deve ser > 0 (recebido: " + quotaBytes + ")");
        }

        String token = null;
        if (args.length == 4) {
            token = args[3].trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Argumento opcional [token] não pode ser vazio.");
            }
        }

        return new ReplicaService(id, uri, quotaBytes, token);
    }

    public static void main(String[] args) {
        try {
            validarWire();
            ReplicaService servico = criarDeArgumentos(args);
            servico.inicializarArmazenamentoEChaves();

            Runtime.getRuntime().addShutdownHook(
                    new Thread(servico::parar, "encerramento-replica-" + servico.getId()));

            servico.iniciar();
            servico.aguardarEncerramento();
        } catch (IllegalArgumentException | IOException | GeneralSecurityException | ExceptionInInitializerError e) {
            Throwable causa = (e instanceof ExceptionInInitializerError && e.getCause() != null)
                    ? e.getCause()
                    : e;
            System.err.println("ERRO: " + causa.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ERRO: Execução interrompida.");
            System.exit(1);
        }
    }
}
