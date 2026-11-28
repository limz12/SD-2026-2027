package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Placement {

    private static final class CandidatoPontuado {
        final ReplicaRegistry.SnapshotReplica replica;
        final double score;

        CandidatoPontuado(ReplicaRegistry.SnapshotReplica replica, double score) {
            this.replica = replica;
            this.score = score;
        }
    }

    private Placement() {
    }

    /**
     * Seleciona até {@code count} réplicas usando Weighted Rendezvous Hashing:
     * score(c, r) = livre_r^ALFA / -ln(u_{c,r}), filtrando livre_r >= tamanho + MARGEM_BYTES.
     */
    public static List<String> choose(
            String chunkId,
            long tamanho,
            int count,
            List<ReplicaRegistry.SnapshotReplica> vivas,
            Set<String> exclude) {
        Objects.requireNonNull(chunkId, "chunkId obrigatório");
        if (tamanho < 0L) {
            throw new IllegalArgumentException("tamanho deve ser >= 0");
        }
        if (count <= 0 || vivas == null || vivas.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> excluidas = (exclude != null) ? exclude : Collections.emptySet();
        long espacoMinimoNecessario = tamanho + Wire.MARGEM_BYTES;
        List<CandidatoPontuado> candidatos = new ArrayList<>();

        for (ReplicaRegistry.SnapshotReplica replica : vivas) {
            if (replica == null || excluidas.contains(replica.id)) {
                continue;
            }
            if (replica.estado == ReplicaRegistry.EstadoReplica.MORTA) {
                continue;
            }
            long livre = replica.livreBytes();
            if (livre < espacoMinimoNecessario || livre <= 0L) {
                continue;
            }
            double score = calcularScoreWrh(chunkId, replica.id, livre, Wire.ALFA);
            candidatos.add(new CandidatoPontuado(replica, score));
        }

        if (candidatos.isEmpty()) {
            return Collections.emptyList();
        }

        candidatos.sort(Comparator.comparingDouble((CandidatoPontuado c) -> c.score).reversed()
                .thenComparing(c -> c.replica.id));

        int limite = Math.min(count, candidatos.size());
        List<String> escolhidas = new ArrayList<>(limite);
        for (int i = 0; i < limite; i++) {
            escolhidas.add(candidatos.get(i).replica.id);
        }
        return Collections.unmodifiableList(escolhidas);
    }

    private static double calcularScoreWrh(String chunkId, String idReplica, long livreBytes, double alfa) {
        double u = uniformeDeterministico(chunkId, idReplica);
        double denominador = -Math.log(u);
        double numerador = (alfa == 0.0) ? 1.0 : Math.pow((double) livreBytes, alfa);
        return numerador / denominador;
    }

    private static double uniformeDeterministico(String chunkId, String idReplica) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((chunkId + "|" + idReplica).getBytes(StandardCharsets.UTF_8));
            long bits = ByteBuffer.wrap(hash).getLong() >>> 11;
            return (bits + 0.5d) / (double) (1L << 53);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
