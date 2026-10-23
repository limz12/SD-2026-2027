package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CausalCheck {

    static class Entry {
        final int k;
        final SensorRecord r;
        Entry(int k, SensorRecord r) { this.k = k; this.r = r; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: CausalCheck <N> <pasta>");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path pasta = Paths.get(args[1]);

        List<Entry> entries = new ArrayList<>();
        Pattern pat = Pattern.compile("lider-p(\\d+)\\.txt");

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pasta, "lider-p*.txt")) {
            for (Path p : ds) {
                Matcher m = pat.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int k = Integer.parseInt(m.group(1));
                if (k < 0 || k >= n) continue;
                if (!Files.exists(p)) continue;
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    SensorRecord r = SensorRecord.fromLine(line);
                    entries.add(new Entry(k, r));
                    // verifica V[k] == seq
                    if (r.getVector().get(k) != r.getSeq()) {
                        System.out.println("VIOLACAO V[k]!=seq: p" + k + " seq=" + r.getSeq() + " V[" + k + "]=" + r.getVector().get(k));
                    }
                }
            }
        }

        long causalAB = 0; // a->b
        long causalBA = 0; // b->a
        long concurrent = 0;
        long concurrentDistinctL = 0;
        long violacoesRelogio = 0;

        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                Entry a = entries.get(i);
                Entry b = entries.get(j);
                if (a.k == b.k) continue;
                VectorClock.Order o = a.r.getVector().compare(b.r.getVector());
                if (o == VectorClock.Order.BEFORE) {
                    causalAB++;
                    if (!(a.r.getLamport() < b.r.getLamport())) {
                        violacoesRelogio++;
                        System.out.println("VIOLACAO relogio: p" + a.k + "#" + a.r.getSeq() + " -> p" + b.k + "#" + b.r.getSeq() + " mas L " + a.r.getLamport() + " !< " + b.r.getLamport());
                    }
                } else if (o == VectorClock.Order.AFTER) {
                    causalBA++;
                    if (!(b.r.getLamport() < a.r.getLamport())) {
                        violacoesRelogio++;
                        System.out.println("VIOLACAO relogio: p" + b.k + "#" + b.r.getSeq() + " -> p" + a.k + "#" + a.r.getSeq() + " mas L " + b.r.getLamport() + " !< " + a.r.getLamport());
                    }
                } else if (o == VectorClock.Order.CONCURRENT) {
                    concurrent++;
                    if (a.r.getLamport() != b.r.getLamport()) {
                        concurrentDistinctL++;
                    }
                }
            }
        }

        System.out.println("Registos lidos: " + entries.size());
        System.out.println("Pares entre particoes causalmente a->b: " + causalAB);
        System.out.println("Pares entre particoes causalmente b->a: " + causalBA);
        System.out.println("Pares entre particoes concorrentes: " + concurrent);
        System.out.println("Pares concorrentes com L distinto: " + concurrentDistinctL);
        if (concurrent > 0) {
            System.out.println("Proporcao concorrentes com L distinto: " + (100.0 * concurrentDistinctL / concurrent) + "%");
        }
        System.out.println("Violacoes da condicao de relogio: " + violacoesRelogio);

        // escreve vista-lamport.txt ordenado por (L, k)
        List<Entry> ordenados = new ArrayList<>(entries);
        ordenados.sort((x, y) -> {
            int c = Long.compare(x.r.getLamport(), y.r.getLamport());
            if (c != 0) return c;
            return Integer.compare(x.k, y.k);
        });
        Path out = pasta.resolve("vista-lamport.txt");
        List<String> linhas = new ArrayList<>();
        for (Entry e : ordenados) {
            linhas.add(e.r.toLine());
        }
        Files.write(out, linhas, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Escrito: " + out);
    }
}
