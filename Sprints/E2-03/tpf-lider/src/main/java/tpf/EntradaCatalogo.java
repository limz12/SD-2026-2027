package tpf;

import java.util.List;

/** Entrada imutável do catálogo de chunks selados. */
public record EntradaCatalogo(String chunkId, long lsnDe, long lsnAte, long tamanho,
                              List<String> replicas, Estado estado) {
    public enum Estado { SELADO, DURAVEL }

    public EntradaCatalogo {
        if (chunkId == null || !chunkId.matches("[0-9a-f]{64}") || lsnDe <= 0
                || lsnAte < lsnDe || tamanho < 0 || estado == null) {
            throw new IllegalArgumentException("Entrada de catálogo inválida");
        }
        replicas = List.copyOf(replicas);
        if (replicas.stream().anyMatch(id -> !ReplicaRegistry.idValido(id))
                || replicas.stream().distinct().count() != replicas.size()) {
            throw new IllegalArgumentException("Lista de réplicas inválida");
        }
    }

    public String linha() {
        return chunkId + ";" + lsnDe + ";" + lsnAte + ";" + tamanho + ";"
                + String.join(",", replicas) + ";" + estado;
    }
}
