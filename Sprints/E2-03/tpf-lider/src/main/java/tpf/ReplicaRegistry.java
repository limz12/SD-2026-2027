package tpf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Estado de heartbeat e reservas de espaço das réplicas conhecidas. */
public final class ReplicaRegistry {
    public enum Estado { VIVA, SUSPEITA, MORTA }

    public static final class ReplicaInfo {
        private final String id;
        private volatile Estado estado;
        private volatile long quota, usados, reservados, ultimoHeartbeat;

        private ReplicaInfo(String id) { this.id = id; this.estado = Estado.MORTA; }
        public String id() { return id; }
        public synchronized Estado estado() { return estado; }
        public synchronized long quota() { return quota; }
        public synchronized long usados() { return usados; }
        public synchronized long reservados() { return reservados; }
        public synchronized long livre() { return Math.max(0, quota - usados - reservados); }
        public synchronized long ultimoHeartbeat() { return ultimoHeartbeat; }
    }

    private final Map<String, ReplicaInfo> replicas = new LinkedHashMap<>();
    private final Consumer<String> eventos;

    public ReplicaRegistry(Consumer<String> eventos) {
        this.eventos = Objects.requireNonNull(eventos);
    }

    public synchronized void onHeartbeat(String id, long usados, long quota) {
        if (!idValido(id) || usados < 0 || quota < 0) {
            throw new IllegalArgumentException("Heartbeat inválido");
        }
        ReplicaInfo replica = replicas.computeIfAbsent(id, ReplicaInfo::new);
        replica.usados = usados;
        replica.quota = quota;
        replica.ultimoHeartbeat = System.currentTimeMillis();
        estado(replica, Estado.VIVA);
    }

    public synchronized void atualizarEstados(long agora) {
        for (ReplicaInfo replica : replicas.values()) {
            long idade = Math.max(0, agora - replica.ultimoHeartbeat);
            if (idade >= Wire.MORTA_MS) estado(replica, Estado.MORTA);
            else if (idade >= Wire.SUSPEITA_MS) estado(replica, Estado.SUSPEITA);
        }
    }

    private void estado(ReplicaInfo replica, Estado novo) {
        Estado antigo = replica.estado;
        if (antigo != novo) {
            replica.estado = novo;
            eventos.accept("ESTADO " + replica.id + " " + antigo + " -> " + novo);
        }
    }

    public synchronized List<ReplicaInfo> reserveAndChoose(
            String chunkId, long tamanho, int count, Set<String> exclude) {
        if (tamanho < 0 || count < 0) throw new IllegalArgumentException("Tamanho/count inválido");
        List<ReplicaInfo> candidates = new ArrayList<>();
        for (ReplicaInfo replica : replicas.values()) {
            if (replica.estado != Estado.MORTA) candidates.add(replica);
        }
        List<ReplicaInfo> selected = Placement.choose(chunkId, tamanho, count, candidates, exclude);
        for (ReplicaInfo replica : selected) replica.reservados = Math.addExact(replica.reservados, tamanho);
        return List.copyOf(selected);
    }

    public synchronized void confirmarReserva(String id, long tamanho) {
        ReplicaInfo replica = require(id);
        if (tamanho < 0 || replica.reservados < tamanho) {
            throw new IllegalStateException("Reserva inexistente para " + id);
        }
        replica.reservados -= tamanho;
        replica.usados = Math.addExact(replica.usados, tamanho);
    }

    public synchronized void libertar(String id, long tamanho) {
        ReplicaInfo replica = replicas.get(id);
        if (replica == null || tamanho < 0 || replica.reservados < tamanho) return;
        replica.reservados -= tamanho;
    }

    public synchronized List<ReplicaInfo> todos() {
        return List.copyOf(replicas.values());
    }

    public synchronized ReplicaInfo obter(String id) { return replicas.get(id); }

    public synchronized void remover(String id) {
        ReplicaInfo removida = replicas.remove(id);
        if (removida != null) eventos.accept("REPLICA-REMOVIDA " + id);
    }

    private ReplicaInfo require(String id) {
        ReplicaInfo replica = replicas.get(id);
        if (replica == null) throw new IllegalArgumentException("Réplica desconhecida: " + id);
        return replica;
    }

    static boolean idValido(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }
}
