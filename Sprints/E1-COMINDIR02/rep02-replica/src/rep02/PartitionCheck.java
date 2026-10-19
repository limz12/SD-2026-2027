package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Verifica a consistência dos ficheiros de um sistema particionado.
 *
 * Uso: java -cp out rep02.PartitionCheck <N> <pasta>
 * A pasta tem de conter os lider-p<k>.txt e os replica-<n>-p<k>.txt (copiados
 * dos três projetos).
 *
 * Para cada partição k verifica: (1) todos os registos do líder têm of(sensor)
 * == k; (2) a sequência
 * de seq começa em 1, sem saltos nem repetições; (3) quantas réplicas a guardam
 * (esperado R = 2) e,
 * para cada uma, se o ficheiro é IGUAL ao do líder, um PREFIXO dele (quantos
 * registos faltam) ou
 * DIVERGENTE; (4) nº de registos e de sensores distintos.
 */
public class PartitionCheck {
    static final int R_ESPERADO = 2;
    static final Pattern LIDER = Pattern.compile("lider-p(\\d+)\\.txt");
    static final Pattern REPLICA = Pattern.compile("replica-(\\w+)-p(\\d+)\\.txt");

    // lê um ficheiro de registos; devolve as linhas (normalizadas por
    // SensorRecord.toLine)
    static List<SensorRecord> ler(Path p) throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!line.isBlank())
                out.add(SensorRecord.fromLine(line));
        }
        return out;
    }

    // null se a sequência for 1,2,3,...; caso contrário, descrição do primeiro
    // problema
    static String verificarSequencia(List<SensorRecord> regs) {
        for (int i = 0; i < regs.size(); i++) {
            long esperado = i + 1;
            long seq = regs.get(i).getSeq();
            if (seq != esperado) {
                String tipo = seq < esperado ? "repetição" : "salto";
                return tipo + " na linha " + (i + 1) + " (seq=" + seq + ", esperado " + esperado + ")";
            }
        }
        return null;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Uso: PartitionCheck <N> <pasta>");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        Partitioner part = new Partitioner(n);

        Map<Integer, Path> lideres = new TreeMap<>();
        Map<Integer, Map<String, Path>> replicas = new TreeMap<>(); // k -> (id da réplica -> ficheiro)
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.collect(Collectors.toList())) {
                String nome = f.getFileName().toString();
                Matcher m = LIDER.matcher(nome);
                if (m.matches()) {
                    lideres.put(Integer.parseInt(m.group(1)), f);
                    continue;
                }
                m = REPLICA.matcher(nome);
                if (m.matches()) {
                    replicas.computeIfAbsent(Integer.parseInt(m.group(2)), x -> new TreeMap<>()).put(m.group(1), f);
                }
            }
        }

        boolean tudoOk = true;
        long somaParticoes = 0;
        for (int k = 0; k < n; k++) {
            System.out.println("=== Partição " + k + " ===");
            Path pl = lideres.get(k);
            if (pl == null) {
                System.out.println("  sem ficheiro do líder (lider-p" + k + ".txt)");
                tudoOk = false;
                continue;
            }
            List<SensorRecord> lider;
            try {
                lider = ler(pl);
            } catch (IllegalArgumentException e) {
                System.out.println("  ficheiro do líder ilegível: " + e.getMessage());
                tudoOk = false;
                continue;
            }
            somaParticoes += lider.size();
            Set<String> sensores = new TreeSet<>();
            int errados = 0;
            for (SensorRecord r : lider) {
                sensores.add(r.getSensorId());
                if (part.of(r.getSensorId()) != k)
                    errados++;
            }
            System.out.println("  líder: " + pl.getFileName() + " | registos=" + lider.size()
                    + " | sensores distintos=" + sensores.size() + " " + sensores);
            if (errados == 0) {
                System.out.println("  chave: OK (todos os registos têm of(sensor) == " + k + ")");
            } else {
                System.out.println("  chave: ERRO — " + errados + " registos de outra partição");
                tudoOk = false;
            }
            String seqLider = verificarSequencia(lider);
            if (seqLider == null) {
                System.out.println("  sequência (líder): OK (1.." + lider.size() + ")");
            } else {
                System.out.println("  sequência (líder): ERRO — " + seqLider);
                tudoOk = false;
            }

            Map<String, Path> reps = replicas.getOrDefault(k, Collections.emptyMap());
            System.out.println("  réplicas que guardam p" + k + ": " + reps.size() + " (esperado R=" + R_ESPERADO + ")"
                    + (reps.size() == R_ESPERADO ? " OK" : " ATENÇÃO"));
            if (reps.size() != R_ESPERADO)
                tudoOk = false;

            List<String> linhasLider = lider.stream().map(SensorRecord::toLine).collect(Collectors.toList());
            for (Map.Entry<String, Path> e : reps.entrySet()) {
                List<SensorRecord> rr;
                try {
                    rr = ler(e.getValue());
                } catch (IllegalArgumentException ex) {
                    System.out.println("    replica-" + e.getKey() + ": ilegível — " + ex.getMessage());
                    tudoOk = false;
                    continue;
                }
                List<String> linhas = rr.stream().map(SensorRecord::toLine).collect(Collectors.toList());
                String seqRep = verificarSequencia(rr);
                if (linhas.size() <= linhasLider.size() && linhasLider.subList(0, linhas.size()).equals(linhas)) {
                    int faltam = linhasLider.size() - linhas.size();
                    if (faltam == 0) {
                        System.out.println(
                                "    replica-" + e.getKey() + ": IGUAL ao líder (" + linhas.size() + " registos)");
                    } else {
                        System.out.println("    replica-" + e.getKey() + ": PREFIXO do líder — faltam " + faltam
                                + " registos (tem " + linhas.size() + ")");
                        tudoOk = false;
                    }
                } else {
                    int i = 0;
                    while (i < linhas.size() && i < linhasLider.size() && linhas.get(i).equals(linhasLider.get(i)))
                        i++;
                    System.out.println(
                            "    replica-" + e.getKey() + ": DIVERGENTE — primeira diferença na linha " + (i + 1)
                                    + " (tem " + linhas.size() + " linhas, o líder " + linhasLider.size() + ")"
                                    + (seqRep != null ? " | sequência: " + seqRep : ""));
                    tudoOk = false;
                }
            }
        }

        // total independente: todos os ficheiros lider-p*.txt encontrados, inclusive de
        // partições fora de [0,N)
        long totalFicheiros = 0;
        for (Map.Entry<Integer, Path> e : lideres.entrySet()) {
            try {
                totalFicheiros += ler(e.getValue()).size();
            } catch (IllegalArgumentException ex) {
                // já assinalado acima
            }
            if (e.getKey() >= n) {
                System.out.println("ATENÇÃO: ficheiro " + e.getValue().getFileName() + " fora do âmbito (N=" + n + ")");
                tudoOk = false;
            }
        }
        System.out.println("=== Total ===");
        System.out.println("  registos no sistema (todos os lider-p*.txt) = " + totalFicheiros
                + " | soma das partições 0.." + (n - 1) + " = " + somaParticoes
                + (totalFicheiros == somaParticoes ? " | IGUAL" : " | DIFERENTE"));
        System.out.println(tudoOk ? "RESULTADO: OK" : "RESULTADO: HÁ DIFERENÇAS (ver acima)");
    }
}