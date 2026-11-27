package tpf;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

final class PubSubHubRegistrationTest {
    @TempDir Path data;

    @Test
    void tokenIsConsumedOnlyAfterValidProofAndRegisteredReplicaReconnects() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        LeaderEvents events = new LeaderEvents(data.resolve("events.txt"));
        LinkedBlockingQueue<String> inbound = new LinkedBlockingQueue<>();
        PubSubHub hub = new PubSubHub(port, data, events, (id, message) -> inbound.add(id + ":" + message));
        RegistrationClient first = null;
        RegistrationClient reused = null;
        RegistrationClient reconnect = null;
        hub.start();
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String token = hub.gerarToken();
            first = new RegistrationClient(port, "node-a", pair, token);
            assertTrue(first.connectBlocking(5, TimeUnit.SECONDS));
            assertTrue(first.authenticated.await(5, TimeUnit.SECONDS));
            assertEquals("node-a:HB;node-a;7;1000", inbound.poll(5, TimeUnit.SECONDS));
            hub.publish("tpf-r-node-a", "STORE;chunk;header;bin");
            assertEquals("PUB;tpf-r-node-a;STORE;chunk;header;bin",
                    first.delivered.poll(5, TimeUnit.SECONDS));
            assertEquals(1, Files.readAllLines(data.resolve("replicas.txt")).size());

            KeyPair secondPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            reused = new RegistrationClient(port, "node-b", secondPair, token);
            assertTrue(reused.connectBlocking(5, TimeUnit.SECONDS));
            assertTrue(reused.closed.await(5, TimeUnit.SECONDS));
            assertEquals(1, Files.readAllLines(data.resolve("replicas.txt")).size());

            reconnect = new RegistrationClient(port, "node-a", pair, null);
            assertTrue(reconnect.connectBlocking(5, TimeUnit.SECONDS));
            assertTrue(reconnect.authenticated.await(5, TimeUnit.SECONDS));
            assertEquals(1, Files.readAllLines(data.resolve("replicas.txt")).size());
        } finally {
            if (first != null && first.isOpen()) first.closeBlocking();
            if (reused != null && reused.isOpen()) reused.closeBlocking();
            if (reconnect != null && reconnect.isOpen()) reconnect.closeBlocking();
            hub.stop(1000);
        }
    }

    private static final class RegistrationClient extends WebSocketClient {
        private final String id;
        private final KeyPair keys;
        private final String token;
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final LinkedBlockingQueue<String> delivered = new LinkedBlockingQueue<>();

        RegistrationClient(int port, String id, KeyPair keys, String token) {
            super(URI.create("ws://127.0.0.1:" + port));
            this.id = id;
            this.keys = keys;
            this.token = token;
        }

        @Override public void onOpen(ServerHandshake handshake) {
            String ola = token == null ? "OLA;" + id : "OLA;" + id + ";"
                    + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + ";" + token;
            send(ola);
        }

        @Override public void onMessage(String message) {
            if (message.startsWith("DESAFIO;")) {
                try {
                    String nonce = message.substring("DESAFIO;".length());
                    Signature signer = Signature.getInstance("Ed25519");
                    signer.initSign(keys.getPrivate());
                    signer.update(("tpf-registo|" + id + "|" + nonce).getBytes(StandardCharsets.UTF_8));
                    send("PROVA;" + id + ";" + Base64.getEncoder().encodeToString(signer.sign()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            } else if (message.equals("AUTENTICADO;" + id)) {
                authenticated.countDown();
                send("SUB;tpf-r-" + id);
                send("SUB;tpf-ctl");
                send("PUB;tpf-lider;HB;" + id + ";7;1000");
            } else {
                delivered.add(message);
            }
        }

        @Override public void onClose(int code, String reason, boolean remote) { closed.countDown(); }
        @Override public void onError(Exception error) { }
    }
}
