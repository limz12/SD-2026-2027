package tpf;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Coordena STORE, ACK, reservas, rondas e durabilidade dos chunks. */
public final class ReplicationManager implements AutoCloseable {
    private static final class WriteState {
        final SealedChunk chunk;
        final Set<String> tried = new HashSet<>();
        final Map<String, Long> pending = new HashMap<>();
        int round;
        ScheduledFuture<?> timeout;
        WriteState(SealedChunk chunk) { this.chunk = chunk; }
    }

    private final ReplicaRegistry registry;
    private final Catalog catalog;
    private final PubSubHub hub;
    private final LeaderEvents events;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tpf-replicacao");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, WriteState> writes = new HashMap<>();
    private final Object lock = new Object();

    public ReplicationManager(ReplicaRegistry registry, Catalog catalog, PubSubHub hub, LeaderEvents events) {
        this.registry = registry;
        this.catalog = catalog;
        this.hub = hub;
        this.events = events;
    }

    public void onSealed(SealedChunk chunk) {
        synchronized (lock) {
            try {
                catalog.adicionar(new EntradaCatalogo(chunk.id(), chunk.lsnDe(), chunk.lsnAte(),
                        chunk.tamanho(), java.util.List.of(), EntradaCatalogo.Estado.SELADO));
                events.log("CHUNK-CATALOGADO " + chunk.id());
                if (catalog.copias(chunk.id()).size() < Wire.R) {
                    WriteState state = writes.computeIfAbsent(chunk.id(), ignored -> new WriteState(chunk));
                    state.tried.addAll(catalog.copias(chunk.id()));
                    iniciarRonda(state);
                }
            } catch (IOException | RuntimeException e) {
                events.log("ERRO catálogo " + chunk.id() + ": " + e.getMessage());
            }
        }
    }

    private void iniciarRonda(WriteState state) {
        if (state.round >= Wire.MAX_RONDAS || catalog.copias(state.chunk.id()).size() >= Wire.R) {
            writes.remove(state.chunk.id());
            return;
        }
        state.round++;
        events.log("RONDA " + state.chunk.id() + " " + state.round);
        int count = Wire.R - catalog.copias(state.chunk.id()).size();
        var selected = registry.reserveAndChoose(state.chunk.id(), state.chunk.tamanho(), count, state.tried);
        if (catalog.copias(state.chunk.id()).size() + selected.size() < Wire.R) {
            events.log("SUBREPLICADO " + state.chunk.id() + " "
                    + (catalog.copias(state.chunk.id()).size() + selected.size()) + "/" + Wire.R);
        }
        for (ReplicaRegistry.ReplicaInfo replica : selected) {
            String id = replica.id();
            state.tried.add(id);
            state.pending.put(id, state.chunk.tamanho());
            events.log("STORE " + state.chunk.id() + " -> " + id);
            String message = "STORE;" + state.chunk.id() + ";"
                    + Base64.getEncoder().encodeToString(state.chunk.header()) + ";"
                    + Base64.getEncoder().encodeToString(state.chunk.bin());
            hub.publish("tpf-r-" + id, message);
        }
        if (state.pending.isEmpty() && state.round >= Wire.MAX_RONDAS) {
            writes.remove(state.chunk.id());
            return;
        }
        state.timeout = timers.schedule(() -> timeout(state.chunk.id()), Wire.ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public void onReplicaMessage(String authenticatedId, String message) {
        String[] f = message.split(";", -1);
        if (f.length == 4 && f[0].equals("HB")) {
            if (!authenticatedId.equals(f[1])) {
                events.log("ERRO heartbeat ID não corresponde à ligação " + authenticatedId);
                return;
            }
            try {
                registry.onHeartbeat(authenticatedId, Long.parseLong(f[2]), Long.parseLong(f[3]));
            } catch (RuntimeException e) {
                events.log("ERRO heartbeat " + authenticatedId + ": " + e.getMessage());
            }
            return;
        }
        if (f.length == 4 && f[0].equals("ACK")) {
            processarAck(authenticatedId, f[1], f[2], f[3]);
            return;
        }
        if (f.length >= 2 && f[0].equals("INVENTARIO") && authenticatedId.equals(f[1])) return;
        events.log("ERRO mensagem de réplica inválida " + authenticatedId);
    }

    private void processarAck(String authenticatedId, String claimedId, String chunkId, String hash) {
        synchronized (lock) {
            WriteState state = writes.get(chunkId);
            String reason = null;
            if (!authenticatedId.equals(claimedId)) reason = "id da ligação não corresponde";
            else if (state == null || !state.pending.containsKey(authenticatedId)) reason = "STORE não pendente";
            else if (!chunkId.equals(hash)) reason = "hash não corresponde ao chunkId";
            else if (!chunkId.matches("[0-9a-f]{64}")) reason = "chunkId inválido";
            if (reason != null) {
                events.log("ACK-REJEITADO " + chunkId + " " + authenticatedId + " " + reason);
                return;
            }
            long size = state.pending.remove(authenticatedId);
            try {
                registry.confirmarReserva(authenticatedId, size);
                int copiesBefore = catalog.copias(chunkId).size();
                boolean durable = copiesBefore + 1 >= Math.max(Wire.W_MIN, Wire.R / 2 + 1);
                catalog.confirmar(chunkId, authenticatedId, durable);
                events.log("ACK " + chunkId + " " + authenticatedId);
                if (durable && copiesBefore < Math.max(Wire.W_MIN, Wire.R / 2 + 1)) {
                    events.log("DURAVEL " + chunkId);
                }
                if (catalog.copias(chunkId).size() >= Wire.R) {
                    if (state.timeout != null) state.timeout.cancel(false);
                    writes.remove(chunkId);
                }
            } catch (IOException | RuntimeException e) {
                events.log("ERRO ao confirmar ACK " + chunkId + ": " + e.getMessage());
            }
        }
    }

    private void timeout(String chunkId) {
        synchronized (lock) {
            WriteState state = writes.get(chunkId);
            if (state == null) return;
            for (Map.Entry<String, Long> pending : state.pending.entrySet()) {
                registry.libertar(pending.getKey(), pending.getValue());
            }
            state.pending.clear();
            if (catalog.copias(chunkId).size() < Wire.R && state.round < Wire.MAX_RONDAS) {
                iniciarRonda(state);
            } else {
                writes.remove(chunkId);
            }
        }
    }

    public Catalog catalog() { return catalog; }
    public ReplicaRegistry registry() { return registry; }

    @Override public void close() {
        timers.shutdownNow();
        synchronized (lock) {
            for (WriteState state : writes.values()) {
                if (state.timeout != null) state.timeout.cancel(false);
                state.pending.forEach(registry::libertar);
            }
            writes.clear();
        }
    }
}
