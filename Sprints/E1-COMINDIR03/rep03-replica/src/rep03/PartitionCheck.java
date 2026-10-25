package rep03;

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
        int n = Integer.parseInt(args[0]);
        Partitioner partitioner = new Partitioner(n);
        Path pasta = Paths.get(args[1]);
        Map<Integer, List<String>> lideres = new TreeMap<>();
        Map<Integer, List<List<String>>> replicas = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pasta)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                String nome = path.getFileName().toString();
                Matcher m = LIDER.matcher(nome);
                if (m.matches()) {
                    lideres.put(Integer.parseInt(m.group(1)), lerLinhas(path));
                    continue;
                }
                m = REPLICA.matcher(nome);
                if (m.matches()) replicas.computeIfAbsent(Integer.parseInt(m.group(2)), ignored -> new ArrayList<>()).add(lerLinhas(path));
            }
        }
        for (int k = 0; k < n; k++) {
            List<String> linhasLider = lideres.get(k);
            if (linhasLider == null) {
                System.out.println("Partição " + k + ": sem ficheiro de líder");
                continue;
            }
            List<SensorRecord> regsLider = new ArrayList<>();
            boolean ok = true;
            for (String linha : linhasLider) {
                try { regsLider.add(SensorRecord.fromLine(linha)); }
                catch (IllegalArgumentException e) { System.out.println("Partição " + k + ": linha inválida no líder: " + linha); ok = false; }
            }
            long esperado = 1;
            for (SensorRecord r : regsLider) {
                if (r.getSeq() != esperado++) { System.out.println("Partição " + k + ": sequência inválida no registo " + r.getSeq()); ok = false; }
                if (partitioner.of(r.getSensorId()) != k) { System.out.println("Partição " + k + ": sensor " + r.getSensorId() + " pertence a p" + partitioner.of(r.getSensorId())); ok = false; }
            }
            System.out.println("Partição " + k + ": líder " + (ok ? "OK" : "COM ERROS") + " (" + regsLider.size() + " registos)");
            List<List<String>> reps = replicas.get(k);
            if (reps == null) { System.out.println("  sem réplicas"); continue; }
            for (List<String> linhasRep : reps) {
                List<SensorRecord> regsRep = new ArrayList<>();
                for (String linha : linhasRep) regsRep.add(SensorRecord.fromLine(linha));
                boolean prefixo = regsRep.size() <= regsLider.size();
                for (int i = 0; prefixo && i < regsRep.size(); i++) prefixo = regsRep.get(i).toLine().equals(regsLider.get(i).toLine());
                System.out.println("  réplica: " + (prefixo ? "OK (prefixo/igual ao líder)" : "DIVERGE do líder") + " (" + regsRep.size() + " registos)");
            }
        }
    }

    private static List<String> lerLinhas(Path path) throws IOException {
        List<String> out = new ArrayList<>();
        for (String linha : Files.readAllLines(path, StandardCharsets.UTF_8)) if (!linha.isBlank()) out.add(linha);
        return out;
    }
}
