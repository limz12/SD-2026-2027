package tpf;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

final class PlacementAndCatalogTest {
    @TempDir Path data;

    @Test
    void placementIsDeterministicDistinctAndHonorsExclusions() {
        ReplicaRegistry registry = new ReplicaRegistry(ignored -> { });
        registry.onHeartbeat("replica-a", 0, 1_000_000);
        registry.onHeartbeat("replica-b", 0, 1_000_000);
        registry.onHeartbeat("replica-c", 0, 1_000_000);

        var first = Placement.choose("a".repeat(64), 100, 3, registry.todos(), Set.of());
        var again = Placement.choose("a".repeat(64), 100, 3, registry.todos(), Set.of());
        assertEquals(first.stream().map(ReplicaRegistry.ReplicaInfo::id).toList(),
                again.stream().map(ReplicaRegistry.ReplicaInfo::id).toList());
        assertEquals(3, first.stream().map(ReplicaRegistry.ReplicaInfo::id).distinct().count());
        assertFalse(Placement.choose("a".repeat(64), 100, 3, registry.todos(),
                Set.of(first.get(0).id())).stream().anyMatch(r -> r.id().equals(first.get(0).id())));
    }

    @Test
    void registryReservationsAreAtomicAgainstAvailableSpace() {
        ReplicaRegistry registry = new ReplicaRegistry(ignored -> { });
        registry.onHeartbeat("replica-a", 0, Wire.MARGEM_BYTES + 100);
        var reserved = registry.reserveAndChoose("b".repeat(64), 100, 1, Set.of());
        assertEquals(1, reserved.size());
        assertTrue(registry.reserveAndChoose("c".repeat(64), 1, 1, Set.of()).isEmpty());
        registry.confirmarReserva("replica-a", 100);
        assertEquals(100, registry.obter("replica-a").usados());
        assertEquals(0, registry.obter("replica-a").reservados());
    }

    @Test
    void heartbeatTransitionsFromAliveToSuspectAndDead() {
        ReplicaRegistry registry = new ReplicaRegistry(ignored -> { });
        registry.onHeartbeat("replica-a", 0, 1000);
        ReplicaRegistry.ReplicaInfo replica = registry.obter("replica-a");
        long lastHeartbeat = replica.ultimoHeartbeat();

        assertEquals(ReplicaRegistry.Estado.VIVA, replica.estado());
        registry.atualizarEstados(lastHeartbeat + Wire.SUSPEITA_MS);
        assertEquals(ReplicaRegistry.Estado.SUSPEITA, replica.estado());
        registry.atualizarEstados(lastHeartbeat + Wire.MORTA_MS);
        assertEquals(ReplicaRegistry.Estado.MORTA, replica.estado());
    }

    @Test
    void catalogPersistsCopiesAndDurabilityAtomically() throws Exception {
        Path path = data.resolve("catalogo.txt");
        String chunk = "c".repeat(64);
        Catalog catalog = new Catalog(path);
        catalog.adicionar(new EntradaCatalogo(chunk, 1, 3, 4096,
                java.util.List.of(), EntradaCatalogo.Estado.SELADO));
        catalog.confirmar(chunk, "replica-a", false);
        catalog.confirmar(chunk, "replica-b", true);
        Catalog recovered = new Catalog(path);
        assertEquals(EntradaCatalogo.Estado.DURAVEL, recovered.obter(chunk).estado());
        assertEquals(java.util.List.of("replica-a", "replica-b"), recovered.copias(chunk));
        assertFalse(Files.exists(data.resolve("catalogo.txt.tmp")));
    }

    @Test
    void challengeProofBindsIdAndFreshNonce() throws Exception {
        PubSubHub hub = new PubSubHub(0, data, new LeaderEvents(data.resolve("events.txt")),
                (id, message) -> { });
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] nonce = new byte[32];
        java.util.Arrays.fill(nonce, (byte) 7);
        String nonceB64 = Base64.getEncoder().encodeToString(nonce);
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(("tpf-registo|replica-a|" + nonceB64).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String proof = Base64.getEncoder().encodeToString(signer.sign());

        assertTrue(hub.verificarProva("replica-a", nonce, proof, pair.getPublic()));
        assertFalse(hub.verificarProva("replica-b", nonce, proof, pair.getPublic()));
        nonce[0]++;
        assertFalse(hub.verificarProva("replica-a", nonce, proof, pair.getPublic()));
    }
}
