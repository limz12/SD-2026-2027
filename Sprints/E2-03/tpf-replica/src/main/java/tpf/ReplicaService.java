package tpf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Processo de réplica standalone: autenticação, heartbeat e armazenamento de chunks cifrados. */
public final class ReplicaService implements WebSocket.Listener, AutoCloseable {
    private final String id;
    private final URI uri;
    private final long quota;
    private final String token;
    private final Path data;
    private final Path chunks;
    private final ReplicaStore store;
    private final PrivateKey privateKey;
    private final byte[] publicKey;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private volatile boolean registered;
    private volatile long used;
    private volatile long backoff = 1000;
    private final StringBuilder fragmentedText = new StringBuilder();

    public ReplicaService(String id, URI uri, long quota, String token)
            throws java.io.IOException, java.security.GeneralSecurityException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || uri == null || quota <= 0
                || !("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("ID, URL ou quota inválidos");
        }
        this.id = id;
        this.uri = uri;
        this.quota = quota;
        this.token = token;
        this.data = Path.of("dados-" + id).toAbsolutePath().normalize();
        this.chunks = data.resolve("chunks");
        Files.createDirectories(chunks);
        this.store = new ReplicaStore(chunks, quota);
        KeyPair pair = loadOrCreateKeyPair(data);
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic().getEncoded();
        this.registered = Files.exists(data.resolve("registered"));
        this.used = store.used();
        scheduler.scheduleAtFixedRate(this::heartbeat, 0, 2000, TimeUnit.MILLISECONDS);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Uso: ReplicaService <id> <url> <quotaBytes> [token]");
            System.exit(2);
        }
        ReplicaService service = new ReplicaService(args[0], URI.create(args[1]),
                Long.parseLong(args[2]), args.length == 4 ? args[3] : null);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        service.connect();
        service.await();
    }

    private KeyPair loadOrCreateKeyPair(Path dir) throws java.io.IOException, java.security.GeneralSecurityException {
        Path privatePath = dir.resolve("replica.pk8");
        Path publicPath = dir.resolve("replica.pub");
        if (Files.exists(privatePath) || Files.exists(publicPath)) {
            if (!Files.isRegularFile(privatePath) || !Files.isRegularFile(publicPath)) {
                throw new IOException("Par de chaves incompleto");
            }
            ensureOwnerOnly(privatePath);
            ensureOwnerOnly(publicPath);
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey privatePart = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
            var publicPart = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privatePart);
            signer.update("tpf-replica-key-check".getBytes(StandardCharsets.UTF_8));
            byte[] proof = signer.sign();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicPart);
            verifier.update("tpf-replica-key-check".getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(proof)) throw new java.security.GeneralSecurityException("Par de chaves divergente");
            return new KeyPair(publicPart, privatePart);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        writePrivate(privatePath, pair.getPrivate().getEncoded());
        writePrivate(publicPath, pair.getPublic().getEncoded());
        return pair;
    }

    static void writePrivate(Path path, byte[] bytes) throws IOException {
        java.nio.file.attribute.FileAttribute<?> attribute = secureCreationAttribute(path);
        try (FileChannel channel = FileChannel.open(path,
                java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attribute)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw new IOException("Escrita de chave sem progresso");
            }
            channel.force(true);
        }
        restrictOwner(path);
        verifyOwnerOnly(path);
    }

    private static java.nio.file.attribute.FileAttribute<?> secureCreationAttribute(Path path) throws IOException {
        Path parent = path.getParent();
        if (Files.getFileAttributeView(parent, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            return java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }
        var acl = Files.getFileAttributeView(parent, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl == null) throw new IOException("Sem suporte a ACL ou permissões POSIX");
        var entry = ownerAcl(currentUser(path));
        List<java.nio.file.attribute.AclEntry> entries = List.of(entry);
        return new java.nio.file.attribute.FileAttribute<List<java.nio.file.attribute.AclEntry>>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<java.nio.file.attribute.AclEntry> value() { return entries; }
        };
    }

    private static java.util.Set<java.nio.file.attribute.AclEntryPermission> ownerOnlyAclPermissions() {
        return java.util.EnumSet.of(
                java.nio.file.attribute.AclEntryPermission.READ_DATA,
                java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
                java.nio.file.attribute.AclEntryPermission.APPEND_DATA,
                java.nio.file.attribute.AclEntryPermission.READ_ATTRIBUTES,
                java.nio.file.attribute.AclEntryPermission.WRITE_ATTRIBUTES,
                java.nio.file.attribute.AclEntryPermission.READ_NAMED_ATTRS,
                java.nio.file.attribute.AclEntryPermission.WRITE_NAMED_ATTRS,
                java.nio.file.attribute.AclEntryPermission.READ_ACL,
                java.nio.file.attribute.AclEntryPermission.WRITE_ACL,
                java.nio.file.attribute.AclEntryPermission.SYNCHRONIZE);
    }

    private static void restrictOwner(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            return;
        }
        var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl == null) throw new IOException("Sem suporte a ACL ou permissões POSIX");
        acl.setAcl(List.of(ownerAcl(acl.getOwner())));
    }

    private static void ensureOwnerOnly(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            verifyOwnerOnly(path);
            return;
        }
        var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl == null) throw new IOException("Sem suporte a ACL ou permissões POSIX");
        var currentUser = currentUser(path);
        if (!acl.getOwner().equals(currentUser)) {
            throw new IOException("A chave da réplica não pertence ao utilizador atual");
        }
        List<java.nio.file.attribute.AclEntry> entries = acl.getAcl();
        boolean correct = entries.size() == 1
                && entries.get(0).type() == java.nio.file.attribute.AclEntryType.ALLOW
                && entries.get(0).flags().isEmpty()
                && entries.get(0).principal().equals(currentUser)
                && entries.get(0).permissions().equals(ownerOnlyAclPermissions());
        if (!correct) acl.setAcl(List.of(ownerAcl(currentUser)));
        verifyOwnerOnly(path);
    }

    private static java.nio.file.attribute.UserPrincipal currentUser(Path path) throws IOException {
        String username = System.getProperty("user.name");
        if (username == null || username.isBlank()) throw new IOException("Utilizador atual indisponível");
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(username);
    }

    private static java.nio.file.attribute.AclEntry ownerAcl(
            java.nio.file.attribute.UserPrincipal owner) {
        return java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(owner).setPermissions(ownerOnlyAclPermissions()).build();
    }

    private static void verifyOwnerOnly(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            var expected = java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            if (!posix.readAttributes().permissions().equals(expected))
                throw new IOException("Permissões inseguras na chave da réplica");
            return;
        }
        var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl == null || acl.getAcl().size() != 1
                || acl.getAcl().get(0).type() != java.nio.file.attribute.AclEntryType.ALLOW
                || !acl.getAcl().get(0).flags().isEmpty()
                || !acl.getAcl().get(0).permissions().equals(ownerOnlyAclPermissions())
                || !acl.getAcl().get(0).principal().equals(acl.getOwner())) {
            throw new IOException("ACL insegura na chave da réplica");
        }
    }

    public void connect() {
        if (stopped.getCount() == 0) return;
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .newWebSocketBuilder().buildAsync(uri, this)
                .exceptionally(error -> {
                    event("ERRO", "ligação: " + error.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    @Override public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        authenticated = false;
        if (registered) webSocket.sendText("OLA;" + id, true);
        else if (token != null && !token.isBlank()) {
            webSocket.sendText("OLA;" + id + ";" + Base64.getEncoder().encodeToString(publicKey)
                    + ";" + token, true);
        } else {
            event("ERRO", "token obrigatório no primeiro registo");
            webSocket.abort();
            return;
        }
        webSocket.request(1);
    }

    @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence frameText, boolean last) {
        String text;
        synchronized (fragmentedText) {
            fragmentedText.append(frameText);
            if (!last) {
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            text = fragmentedText.toString();
            fragmentedText.setLength(0);
        }
        if (text.startsWith("DESAFIO;")) {
            try {
                String nonceB64 = text.substring("DESAFIO;".length());
                byte[] nonce = Base64.getDecoder().decode(nonceB64);
                if (nonce.length != 32) throw new IllegalArgumentException("Nonce deve conter 32 bytes");
                String signed = "tpf-registo|" + id + "|" + nonceB64;
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(privateKey);
                signer.update(signed.getBytes(StandardCharsets.UTF_8));
                String proof = Base64.getEncoder().encodeToString(signer.sign());
                webSocket.sendText("PROVA;" + id + ";" + proof, true);
            } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                event("ERRO", "falha ao responder ao desafio: " + e.getMessage());
                webSocket.abort();
            }
        } else if (text.equals("AUTENTICADO;" + id) || text.equals("REGISTADO;" + id)) {
            registered = true;
            try {
                Files.writeString(data.resolve("registered"), "ok\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                event("ERRO", "não foi possível guardar o estado de registo: " + e.getMessage());
            }
            authenticated = true;
            backoff = 1000;
            reconnectScheduled.set(false);
            event("LIGADO", uri.toString());
            webSocket.sendText("SUB;tpf-r-" + id, true);
            webSocket.sendText("SUB;tpf-ctl", true);
            publishInventory();
            heartbeat();
        } else if (text.startsWith("PUB;tpf-r-" + id + ";")) {
            handleStore(text.substring(("PUB;tpf-r-" + id + ";").length()));
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    private void handleStore(String message) {
        String[] f = message.split(";", -1);
        if (f.length != 4 || !f[0].equals("STORE")) {
            event("ERRO", "STORE inválido");
            return;
        }
        String chunkId = f[1];
        if (!chunkId.matches("[0-9a-f]{64}")) {
            event("ERRO", "chunkId inválido");
            return;
        }
        try {
            byte[] hdr = Base64.getDecoder().decode(f[2]);
            byte[] bin = Base64.getDecoder().decode(f[3]);
            ReplicaStore.Result result = store.store(chunkId, hdr, bin);
            used = store.used();
            if (result == ReplicaStore.Result.QUOTA) {
                event("QUOTA", chunkId + " requer " + bin.length + " bytes; livre " + (quota - used));
                return;
            }
            if (result == ReplicaStore.Result.STORED) event("STORE", chunkId + " " + bin.length);
            sendAck(chunkId);
        } catch (java.io.IOException | RuntimeException e) {
            event("ERRO", "STORE " + chunkId + ": " + e.getMessage());
        }
    }

    private void sendAck(String chunkId) {
        WebSocket current = socket;
        if (current != null && authenticated) {
            current.sendText("PUB;tpf-lider;ACK;" + id + ";" + chunkId + ";" + chunkId, true);
        }
    }

    private void heartbeat() {
        WebSocket current = socket;
        if (current != null && authenticated) {
            used = store.used();
            current.sendText("PUB;tpf-lider;HB;" + id + ";" + used + ";" + quota, true);
        }
    }

    private void publishInventory() {
        try (var files = Files.list(chunks)) {
            String inventory = files.filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.bin$", ""))
                    .sorted().reduce((a, b) -> a + "," + b).orElse("");
            WebSocket current = socket;
            if (current != null) current.sendText("PUB;tpf-lider;INVENTARIO;" + id + ";" + inventory, true);
        } catch (IOException e) {
            event("ERRO", "inventário: " + e.getMessage());
        }
    }

    @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        authenticated = false;
        event("DESLIGADO", reason);
        scheduleReconnect();
        return CompletableFuture.completedFuture(null);
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        event("ERRO", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (stopped.getCount() == 0 || !reconnectScheduled.compareAndSet(false, true)) return;
        long delay = backoff;
        event("RELIGAR", Long.toString(delay));
        backoff = Math.min(30000, backoff > 15000 ? 30000 : backoff * 2);
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void event(String type, String details) {
        try {
            Files.writeString(data.resolve("eventos-" + id + ".txt"),
                    System.currentTimeMillis() + ";" + type + " " + details.replace('\n', ' ') + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ERRO ao escrever eventos da réplica: " + e.getMessage());
        }
    }

    private void await() throws InterruptedException { stopped.await(); }

    @Override public void close() {
        stopped.countDown();
        scheduler.shutdownNow();
        WebSocket current = socket;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "encerramento");
    }
}
