package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** Arquiva registos que vão ser descartados, ANTES da truncagem do log. */
public final class TruncationAudit {
    private TruncationAudit() { }

    /**
     * Guarda auditId;seq;termo;sensor;temperatura;instante;L;V, uma linha por registo.
     * Devolve o identificador que deve constar no evento TRUNCADO.
     * Se a escrita falhar, lança IOException para impedir truncagem sem rasto.
     */
    public static synchronized String save(int k, int node, List<SensorRecord> discarded)
            throws IOException {
        if (discarded == null) throw new IllegalArgumentException("Registos nulos");
        String id = UUID.randomUUID().toString();
        if (!discarded.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SensorRecord r : discarded) {
                sb.append(id).append(';').append(r.toLine()).append('\n');
            }
            Path file = Paths.get("descartados-p" + k + "-n" + node + ".txt");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return id;
    }
}
