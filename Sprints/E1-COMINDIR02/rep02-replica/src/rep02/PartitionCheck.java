package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PartitionCheck {

    private static final Pattern LIDER = Pattern.compile("^lider-p(\\d+)\\.txt$");
    private static final Pattern REPLICA = Pattern.compile("^replica-(\\d+)-p(\\d+)\\.txt$");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionCheck <N> <pasta>");
            return;
        }
        int N = Integer.parseInt(args[0]);
        Path pasta = Paths.get(args[1]);
        Partitioner partitioner = new Partitioner(N);

        Map<Integer, List<String>> lideres = new TreeMap<>();
        Map<Integer, List<List<String>>> replicas = new TreeMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pasta)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                String nome = path.getFileName().toString();
                Matcher m = LIDER.matcher(nome);
                if (m.matches()) {
                    int k = Integer.parseInt(m.group(1));
                    lideres.put(k, lerLinhas(path));
                    continue;
                }
                m = REPLICA.matcher(nome);
                if (m.matches()) {
                    int k = Integer.parseInt(m.group(2));
                    replicas.computeIfAbsent(k, ignored -> new ArrayList<>()).add(lerLinhas(path));
                }
            }
        }

        for (int k = 0; k < N; k++) {
            List<String> linhasLider = lideres.get(k);
            if (linhasLider == null) {
                System.out.println("Particao " + k + ": sem ficheiro de lider");
                continue;
            }

            List<SensorRecord> regsLider = new ArrayList<>();
            boolean ok = true;
            for (String linha : linhasLider) {
                if (linha.isBlank()) continue;
                try {
                    regsLider.add(SensorRecord.fromLine(linha));
                } catch (IllegalArgumentException e) {
                    System.out.println("Particao " + k + ": linha invalida no lider: " + linha);
                    ok = false;
                }
            }
            if (!ok) continue;

            long esperado = 1;
            for (SensorRecord r : regsLider) {
                if (r.getSeq() != esperado) {
                    System.out.println("Particao " + k + ": sequencia quebrada no seq " + r.getSeq() + " (esperado " + esperado + ")");
                    ok = false;
                    break;
                }
                esperado++;
            }
            if (!ok) continue;

            for (SensorRecord r : regsLider) {
                if (partitioner.of(r.getSensorId()) != k) {
                    System.out.println("Particao " + k + ": registo " + r.getSeq() + " sensor " + r.getSensorId() + " pertence a p" + partitioner.of(r.getSensorId()));
                    ok = false;
                }
            }
            if (!ok) continue;

            System.out.println("Particao " + k + ": lider OK (" + regsLider.size() + " registos, seq 1.." + (regsLider.isEmpty() ? 0 : regsLider.get(regsLider.size() - 1).getSeq()) + ")");

            List<List<String>> reps = replicas.get(k);
            if (reps == null || reps.isEmpty()) {
                System.out.println("  sem replicas");
                continue;
            }
            int repNum = 0;
            for (List<String> linhasRep : reps) {
                repNum++;
                List<SensorRecord> regsRep = new ArrayList<>();
                for (String linha : linhasRep) {
                    if (linha.isBlank()) continue;
                    regsRep.add(SensorRecord.fromLine(linha));
                }
                boolean prefixo = true;
                if (regsRep.size() > regsLider.size()) {
                    prefixo = false;
                } else {
                    for (int i = 0; i < regsRep.size(); i++) {
                        if (!regsRep.get(i).toLine().equals(regsLider.get(i).toLine())) {
                            prefixo = false;
                            break;
                        }
                    }
                }
                System.out.println("  replica #" + repNum + ": " + (prefixo ? "OK (prefixo/igual ao lider)" : "DIVERGE do lider") + " (" + regsRep.size() + " registos)");
            }
        }
    }

    private static List<String> lerLinhas(Path path) throws IOException {
        List<String> out = new ArrayList<>();
        for (String linha : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!linha.isBlank()) out.add(linha);
        }
        return out;
    }
}
