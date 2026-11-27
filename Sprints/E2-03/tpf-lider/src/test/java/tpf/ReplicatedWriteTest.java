package tpf;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReplicatedWriteTest {
    @TempDir Path data;

    @Test
    void sealedChunkBecomesDurableAfterMajorityOfAuthenticatedStoreAcks() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
        LeaderEvents events = new LeaderEvents(data.resolve("events.txt"));
        ReplicaRegistry registry = new ReplicaRegistry(events::log);
        Catalog catalog = new Catalog(data.resolve("catalogo.txt"));
        AtomicReference<ReplicationManager> managerRef = new AtomicReference<>();
        CountDownLatch heartbeatsReceived = new CountDownLatch(3);
        PubSubHub hub = new PubSubHub(port, data, events,
                (id, message) -> {
                    managerRef.get().onReplicaMessage(id, message);
                    if (message.startsWith("HB;")) heartbeatsReceived.countDown();
                });
        ReplicationManager manager = new ReplicationManager(registry, catalog, hub, events);
        managerRef.set(manager);
        ReplicaPeer[] peers = new ReplicaPeer[3];
        hub.start();
        try {
            for (int i = 0; i < peers.length; i++) {
                String id = "replica-" + i;
                KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                peers[i] = new ReplicaPeer(port, id, pair, hub.gerarToken());
                assertTrue(peers[i].connectBlocking(5, TimeUnit.SECONDS));
                assertTrue(peers[i].authenticated.await(5, TimeUnit.SECONDS));
                assertTrue(peers[i].heartbeatSent.await(5, TimeUnit.SECONDS));
            }
            assertTrue(heartbeatsReceived.await(5, TimeUnit.SECONDS));

            byte[] bin = "ciphertext-only".getBytes(StandardCharsets.UTF_8);
            byte[] header = "1;c2FsdA==;bm9uY2U=\n".getBytes(StandardCharsets.UTF_8);
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bin));
            manager.onSealed(new SealedChunk(id, 1, 1, header, bin));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (catalog.copias(id).size() < 3 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(3, catalog.copias(id).size());
            assertEquals(EntradaCatalogo.Estado.DURAVEL, catalog.obter(id).estado());
            assertEquals(1, Files.readAllLines(data.resolve("catalogo.txt")).size());
        } finally {
            manager.close();
            for (ReplicaPeer peer : peers) if (peer != null && peer.isOpen()) peer.closeBlocking();
            hub.stop(1000);
        }
    }

    private static final class ReplicaPeer extends WebSocketClient {
        private final String id;
        private final KeyPair pair;
        private final String token;
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch heartbeatSent = new CountDownLatch(1);

        ReplicaPeer(int port, String id, KeyPair pair, String token) {
            super(URI.create("ws://127.0.0.1:" + port));
            this.id = id;
            this.pair = pair;
            this.token = token;
        }

        @Override public void onOpen(ServerHandshake handshake) {
            send("OLA;" + id + ";" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + ";" + token);
        }

        @Override public void onMessage(String message) {
            if (message.startsWith("DESAFIO;")) {
                try {
                    String nonce = message.substring("DESAFIO;".length());
                    Signature signer = Signature.getInstance("Ed25519");
                    signer.initSign(pair.getPrivate());
                    signer.update(("tpf-registo|" + id + "|" + nonce).getBytes(StandardCharsets.UTF_8));
                    send("PROVA;" + id + ";" + Base64.getEncoder().encodeToString(signer.sign()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            } else if (message.equals("AUTENTICADO;" + id)) {
                authenticated.countDown();
                send("SUB;tpf-r-" + id);
                send("SUB;tpf-ctl");
                send("PUB;tpf-lider;HB;" + id + ";0;1000000");
                heartbeatSent.countDown();
            } else if (message.startsWith("PUB;tpf-r-" + id + ";STORE;")) {
                String[] fields = message.substring(("PUB;tpf-r-" + id + ";").length()).split(";", -1);
                send("PUB;tpf-lider;ACK;" + id + ";" + fields[1] + ";" + fields[1]);
            }
        }

        @Override public void onClose(int code, String reason, boolean remote) { }
        @Override public void onError(Exception error) { }
    }
}
