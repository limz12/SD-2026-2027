package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Escolha determinística ponderada por rendezvous e espaço livre. */
public final class Placement {
    private Placement() { }

    public static List<ReplicaRegistry.ReplicaInfo> choose(
            String chunkId, long tamanho, int count,
            List<ReplicaRegistry.ReplicaInfo> vivas, Set<String> exclude) {
        if (chunkId == null || tamanho < 0 || count < 0) throw new IllegalArgumentException("Argumentos inválidos");
        Set<String> excluded = exclude == null ? Set.of() : new HashSet<>(exclude);
        List<Scored> scored = new ArrayList<>();
        for (ReplicaRegistry.ReplicaInfo replica : vivas) {
            long free = replica.livre();
            if (excluded.contains(replica.id()) || free < tamanho
                    || free - tamanho < Wire.MARGEM_BYTES) continue;
            double u = uniform(chunkId, replica.id());
            double score = Math.pow(free, Wire.ALFA) / -Math.log(u);
            scored.add(new Scored(replica, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(item -> item.replica().id()));
        return scored.stream().limit(count).map(Scored::replica).toList();
    }

    private record Scored(ReplicaRegistry.ReplicaInfo replica, double score) { }

    private static double uniform(String chunk, String replica) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((chunk + "|" + replica).getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(hash).getLong() >>> 11;
            return (value + 0.5) / 9007199254740992.0;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
