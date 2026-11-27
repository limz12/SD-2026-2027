package tpf;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** Hub WebSocket com registo Ed25519 e encaminhamento pub/sub autenticado. */
public final class PubSubHub extends WebSocketServer {
    private enum Fase { NOVA, DESAFIADA, AUTENTICADA }
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Ligacao {
        Fase fase = Fase.NOVA;
        String id, token;
        byte[] nonce;
        PublicKey chave;
        final Set<String> subscricoes = new HashSet<>();
    }

    private record Token(long criadoEm) { }
    private final Path replicasFile;
    private final LeaderEvents eventos;
    private final BiConsumer<String, String> receptor;
    private final Map<WebSocket, Ligacao> ligacoes = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> replicas = new HashMap<>();
    private final Map<String, Token> tokens = new HashMap<>();
    private final Map<String, Set<WebSocket>> subscricoes = new HashMap<>();
    private final Object lock = new Object();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile Exception startupFailure;

    public PubSubHub(int port, Path dados, LeaderEvents eventos,
                     BiConsumer<String, String> receptor) throws IOException {
        super(new InetSocketAddress(port));
        this.replicasFile = dados.resolve("replicas.txt");
        this.eventos = eventos;
        this.receptor = receptor;
        carregarReplicas();
    }

    private void carregarReplicas() throws IOException {
        if (!Files.exists(replicasFile)) return;
        int lineNo = 0;
        for (String line : Files.readAllLines(replicasFile, StandardCharsets.UTF_8)) {
            lineNo++;
            String[] f = line.split(";", -1);
            try {
                if (f.length != 2 || !ReplicaRegistry.idValido(f[0])
                        || replicas.putIfAbsent(f[0], KeyFactory.getInstance("Ed25519")
                                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(f[1]))) ) != null) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception e) {
                throw new IOException("Linha inválida em replicas.txt: " + lineNo, e);
            }
        }
    }

    public String gerarToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        synchronized (lock) { tokens.put(token, new Token(System.currentTimeMillis())); }
        return token;
    }

    public void awaitReady(long timeoutMs) throws IOException, InterruptedException {
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IOException("Timeout ao iniciar o hub WebSocket");
        }
        if (startupFailure != null) throw new IOException("Falha ao iniciar o hub WebSocket", startupFailure);
    }

    public List<String> idsRegistados() {
        synchronized (lock) { return List.copyOf(replicas.keySet()); }
    }

    public void removerReplica(String id) throws IOException {
        synchronized (lock) {
            PublicKey removed = replicas.remove(id);
            try {
                gravarReplicas();
            } catch (IOException e) {
                if (removed != null) replicas.put(id, removed);
                throw e;
            }
            for (Map.Entry<WebSocket, Ligacao> entry : ligacoes.entrySet()) {
                if (id.equals(entry.getValue().id)) entry.getKey().close(1008, "Réplica removida");
            }
        }
    }

    public void publish(String topico, String mensagem) {
        Set<WebSocket> destinatarios;
        synchronized (lock) {
            destinatarios = Set.copyOf(subscricoes.getOrDefault(topico, Set.of()));
        }
        String frame = "PUB;" + topico + ";" + mensagem;
        for (WebSocket socket : destinatarios) {
            if (socket.isOpen()) socket.send(frame);
        }
    }

    @Override public void onOpen(WebSocket connection, ClientHandshake handshake) {
        ligacoes.put(connection, new Ligacao());
    }

    @Override public void onMessage(WebSocket connection, String message) {
        Ligacao state = ligacoes.get(connection);
        if (state == null) return;
        String remetente = null;
        String payload = null;
        try {
            synchronized (lock) {
                String[] fields = message.split(";", 3);
                if (state.fase == Fase.AUTENTICADA && fields.length == 3
                        && fields[0].equals("PUB") && fields[1].equals("tpf-lider")) {
                    remetente = state.id;
                    payload = fields[2];
                } else {
                    processar(connection, state, message);
                }
            }
            if (remetente != null) {
                receptor.accept(remetente, payload);
            }
        } catch (Exception e) {
            eventos.log("ERRO ligação " + state.id + ": " + e.getMessage());
            connection.close(1008, "Protocolo inválido");
        }
    }

    private void processar(WebSocket socket, Ligacao state, String message) throws Exception {
        if (state.fase == Fase.NOVA) {
            String[] f = message.split(";", 4);
            if (f.length < 2 || !f[0].equals("OLA")) throw new IOException("Esperado OLA");
            iniciarDesafio(state, f);
            byte[] nonce = new byte[32];
            RANDOM.nextBytes(nonce);
            state.nonce = nonce;
            state.fase = Fase.DESAFIADA;
            socket.send("DESAFIO;" + Base64.getEncoder().encodeToString(nonce));
            return;
        }
        if (state.fase == Fase.DESAFIADA) {
            String[] f = message.split(";", 3);
            if (f.length != 3 || !f[0].equals("PROVA") || !f[1].equals(state.id))
                throw new IOException("Esperado PROVA para a ligação desafiada");
            byte[] nonce = state.nonce;
            state.nonce = null;
            if (!verificarProva(state.id, nonce, f[2], state.chave))
                throw new IOException("PROVA inválida");
            if (state.token != null) {
                Token token = tokens.get(state.token);
                if (token == null || System.currentTimeMillis() - token.criadoEm() >= Wire.TOKEN_VALIDADE_MS)
                    throw new IOException("Token inexistente ou expirado");
                if (replicas.containsKey(state.id)) throw new IOException("ID já registado");
                replicas.put(state.id, state.chave);
                try {
                    gravarReplicas();
                    tokens.remove(state.token);
                } catch (IOException e) {
                    replicas.remove(state.id);
                    throw e;
                }
            }
            state.token = null;
            state.fase = Fase.AUTENTICADA;
            eventos.log("LIGADO " + state.id);
            socket.send("AUTENTICADO;" + state.id);
            return;
        }
        String[] f = message.split(";", 3);
        if (f[0].equals("OLA")) throw new IOException("OLA repetido");
        if (f[0].equals("SUB") && f.length == 2) {
            String topic = f[1];
            if (!topic.equals("tpf-ctl") && !topic.equals("tpf-r-" + state.id))
                throw new IOException("Subscrição não autorizada: " + topic);
            state.subscricoes.add(topic);
            subscricoes.computeIfAbsent(topic, ignored -> new HashSet<>()).add(socket);
            return;
        }
        if (f[0].equals("PUB") && f.length == 3) {
            if (!"tpf-lider".equals(f[1])) throw new IOException("Publicação não autorizada: " + f[1]);
            return;
        }
        throw new IOException("Mensagem ou ordem inválida");
    }

    private void iniciarDesafio(Ligacao state, String[] fields) throws Exception {
        if (fields.length == 2) {
            state.id = validarId(fields[1]);
            state.chave = replicas.get(state.id);
            if (state.chave == null) throw new IOException("Réplica não registada");
            return;
        }
        if (fields.length != 4) throw new IOException("OLA inválido");
        state.id = validarId(fields[1]);
        PublicKey received = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(fields[2])));
        PublicKey known = replicas.get(state.id);
        if (known != null) {
            if (fields.length != 2) throw new IOException("Réplica registada deve usar OLA;<id>");
            if (!java.util.Arrays.equals(known.getEncoded(), received.getEncoded()))
                throw new IOException("Chave pública diferente para ID registado");
            state.chave = known;
            return;
        }
        if (fields[3].isBlank()) throw new IOException("Token vazio");
        Token token = tokens.get(fields[3]);
        if (token == null || System.currentTimeMillis() - token.criadoEm() >= Wire.TOKEN_VALIDADE_MS)
            throw new IOException("Token inexistente ou expirado");
        state.chave = received;
        state.token = fields[3];
    }

    public boolean verificarProva(String id, byte[] nonce, String signatureB64, PublicKey key) {
        if (nonce == null || key == null || id == null) return false;
        try {
            String nonceB64 = Base64.getEncoder().encodeToString(nonce);
            byte[] message = ("tpf-registo|" + id + "|" + nonceB64).getBytes(StandardCharsets.UTF_8);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }

    private static String validarId(String id) throws IOException {
        if (!ReplicaRegistry.idValido(id)) throw new IOException("ID de réplica inválido");
        return id;
    }

    private void gravarReplicas() throws IOException {
        Path temp = replicasFile.resolveSibling("replicas.txt.tmp");
        List<String> lines = new ArrayList<>();
        replicas.forEach((id, key) -> lines.add(id + ";" + Base64.getEncoder().encodeToString(key.getEncoded())));
        Files.write(temp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temp, replicasFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        Ligacao state = ligacoes.remove(connection);
        if (state == null) return;
        synchronized (lock) {
            for (String topic : state.subscricoes) {
                Set<WebSocket> clients = subscricoes.get(topic);
                if (clients != null) {
                    clients.remove(connection);
                    if (clients.isEmpty()) subscricoes.remove(topic);
                }
            }
        }
        if (state.fase == Fase.AUTENTICADA) eventos.log("DESLIGADO " + state.id);
    }

    @Override public void onError(WebSocket connection, Exception error) {
        if (ready.getCount() > 0) {
            startupFailure = error;
            ready.countDown();
        }
        eventos.log("ERRO WebSocket: " + error.getMessage());
    }

    @Override public void onStart() {
        eventos.log("HUB-PRONTO porto=" + getPort());
        ready.countDown();
    }
}
