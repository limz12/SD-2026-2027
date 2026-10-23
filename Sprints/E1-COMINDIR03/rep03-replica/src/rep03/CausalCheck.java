package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Análise a posteriori das relações de ordem entre registos de partições
 * diferentes.
 *
 * Uso: java -cp out rep03.CausalCheck <N> <pasta> [log-da-réplica]
 * <pasta> contém os lider-p<k>.txt (copiados do projeto do líder); escreve-se
 * lá vista-lamport.txt
 * [log-da-réplica] opcional: consola de uma réplica arrancada com -Dtempos=true
 * (linhas "ESCRITA ...");
 * serve ao ensaio 7(e): procura registos escritos ANTES de uma dependência
 * causal.
 *
 * Verifica: V[k] == seq; classificação de cada par de partições diferentes
 * (a→b, b→a, concorrentes);
 * condição de relógio de Lamport (a→b => L(a) < L(b)); fração dos pares
 * concorrentes com L distinto;
 * se cada alarme tem a sua causa como antecessora causal; custo em bytes dos
 * carimbos.
 */
public class CausalCheck {
    static final Pattern LIDER = Pattern.compile("lider-p(\\d+)\\.txt");
    static final Pattern ESCRITA = Pattern.compile("ESCRITA t=(\\d+) p(\\d+) seq=(\\d+) (\\S+) L=(\\d+) V=(\\S+)");
    static final Pattern CABECALHO = Pattern.compile("p(\\d+)\\(último");

    static class Ev { // um registo, com a sua partição
        final int k;
        final SensorRecord r;

        Ev(int k, SensorRecord r) {
            this.k = k;
            this.r = r;
        }

        String id() {
            return "p" + k + "#" + r.getSeq();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Uso: CausalCheck <N> <pasta> [log-da-réplica]");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        boolean tudoOk = true;

        // ---- 1. leitura e verificação V[k] == seq
        List<List<Ev>> porParticao = new ArrayList<>();
        List<Ev> todos = new ArrayList<>();
        System.out.println("=== Ficheiros dos líderes ===");
        for (int k = 0; k < n; k++) {
            List<Ev> evs = new ArrayList<>();
            Path f = dir.resolve("lider-p" + k + ".txt");
            if (!Files.exists(f)) {
                System.out.println("  p" + k + ": sem ficheiro (lider-p" + k + ".txt)");
                porParticao.add(evs);
                continue;
            }
            int viol = 0, dimErr = 0, descL = 0;
            long lAnt = 0;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank())
                    continue;
                SensorRecord r = SensorRecord.fromLine(line);
                if (r.getVector().toString().split(",").length != n) {
                    dimErr++;
                    continue;
                }
                if (r.getVector().get(k) != r.getSeq())
                    viol++;
                if (r.getLamport() <= lAnt)
                    descL++; // L tem de crescer estritamente dentro da partição
                lAnt = r.getLamport();
                evs.add(new Ev(k, r));
            }
            System.out.println("  p" + k + ": registos=" + evs.size()
                    + " | V[" + k + "]==seq: " + (viol == 0 ? "OK" : "ERRO em " + viol + " linhas")
                    + " | L estritamente crescente: " + (descL == 0 ? "OK" : "ERRO em " + descL + " linhas")
                    + (dimErr > 0 ? " | " + dimErr + " linhas com vetor de dimensão != N (ignoradas)" : ""));
            if (viol > 0 || descL > 0 || dimErr > 0)
                tudoOk = false;
            porParticao.add(evs);
            todos.addAll(evs);
        }
        try (java.util.stream.Stream<Path> ls = Files.list(dir)) {
            for (Path p : (Iterable<Path>) ls::iterator) {
                Matcher m = LIDER.matcher(p.getFileName().toString());
                if (m.matches() && Integer.parseInt(m.group(1)) >= n) {
                    System.out.println("  ATENÇÃO: " + p.getFileName() + " fora do âmbito (N=" + n + ")");
                    tudoOk = false;
                }
            }
        }

        // ---- 2. pares de partições diferentes
        System.out.println("=== Pares de registos de partições diferentes ===");
        long causais = 0, concorrentes = 0, iguais = 0, violL = 0, concLDist = 0, concLIgual = 0;
        List<String> exViol = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long ij = 0, ji = 0, cc = 0;
                for (Ev a : porParticao.get(i)) {
                    for (Ev b : porParticao.get(j)) {
                        VectorClock.Order o = a.r.getVector().compare(b.r.getVector());
                        switch (o) {
                            case BEFORE: // a → b
                                ij++;
                                if (!(a.r.getLamport() < b.r.getLamport())) {
                                    violL++;
                                    if (exViol.size() < 5)
                                        exViol.add(a.id() + " → " + b.id() + " mas L=" + a.r.getLamport()
                                                + " !< L=" + b.r.getLamport());
                                }
                                break;
                            case AFTER: // b → a
                                ji++;
                                if (!(b.r.getLamport() < a.r.getLamport())) {
                                    violL++;
                                    if (exViol.size() < 5)
                                        exViol.add(b.id() + " → " + a.id() + " mas L=" + b.r.getLamport()
                                                + " !< L=" + a.r.getLamport());
                                }
                                break;
                            case EQUAL:
                                iguais++;
                                break;
                            default: // concorrentes
                                cc++;
                                if (a.r.getLamport() != b.r.getLamport())
                                    concLDist++;
                                else
                                    concLIgual++;
                        }
                    }
                }
                causais += ij + ji;
                concorrentes += cc;
                System.out.println(
                        "  p" + i + "↔p" + j + ": p" + i + "→p" + j + "=" + ij + " | p" + j + "→p" + i + "=" + ji
                                + " | concorrentes=" + cc);
            }
        }
        long totalPares = causais + concorrentes + iguais;
        System.out.println("  TOTAL: pares=" + totalPares + " | causalmente relacionados=" + causais
                + " | concorrentes=" + concorrentes + (iguais > 0 ? " | IGUAIS (impossível)=" + iguais : ""));
        System.out.println("  condição de relógio de Lamport (a→b ⇒ L(a)<L(b)): "
                + (violL == 0 ? "OK, sem violações em " + causais + " pares causais"
                        : "VIOLADA em " + violL + " pares"));
        for (String e : exViol)
            System.out.println("      " + e);
        if (violL > 0)
            tudoOk = false;
        if (iguais > 0)
            tudoOk = false;
        System.out
                .println("  concorrentes com L distinto (ordenados, sem significado causal, por Lamport): " + concLDist
                        + " de " + concorrentes + (concorrentes > 0
                                ? String.format(" (%.1f %%)", 100.0 * concLDist / concorrentes)
                                : "")
                        + " | com L igual (empate, decidido pela partição): " + concLIgual);

        // ---- 3. alarmes: a causa é antecessora causal do alarme?
        int totalAlarmes = 0, comCausa = 0, semCausa = 0, semOrigem = 0;
        List<String> exAl = new ArrayList<>();
        for (Ev c : todos) {
            if (!c.r.getSensorId().startsWith("ALARM-"))
                continue;
            totalAlarmes++;
            String base = c.r.getSensorId().substring("ALARM-".length());
            boolean existe = false, antecessora = false;
            for (Ev a : todos) {
                if (a.r.getSensorId().equals(base) && a.r.getTemperature() == c.r.getTemperature()) {
                    existe = true;
                    if (a.r.getVector().compare(c.r.getVector()) == VectorClock.Order.BEFORE) {
                        antecessora = true;
                        break;
                    }
                }
            }
            if (!existe)
                semOrigem++;
            else if (antecessora)
                comCausa++;
            else {
                semCausa++;
                if (exAl.size() < 5)
                    exAl.add(c.id() + " (" + c.r.getSensorId() + " " + c.r.getTemperature() + " V=" + c.r.getVector()
                            + ")");
            }
        }
        System.out.println("=== Alarmes ===");
        System.out.println("  total=" + totalAlarmes + " | com a causa como antecessora causal (V(causa) < V(alarme))="
                + comCausa
                + " | SEM antecessora causal=" + semCausa + " | sem registo de origem nos ficheiros=" + semOrigem);
        for (String e : exAl)
            System.out.println("      alarme sem dependência causal da sua causa: " + e);
        if (semCausa > 0)
            tudoOk = false;

        // ---- 4. vista de Lamport: todos os registos ordenados por (L, k)
        List<Ev> ord = new ArrayList<>(todos);
        ord.sort(Comparator.<Ev>comparingLong(e -> e.r.getLamport()).thenComparingInt(e -> e.k)
                .thenComparingLong(e -> e.r.getSeq()));
        List<String> linhas = new ArrayList<>();
        for (Ev e : ord) {
            linhas.add("L=" + e.r.getLamport() + " p" + e.k + "#" + e.r.getSeq() + " " + e.r.getSensorId() + " "
                    + e.r.getTemperature() + " V=" + e.r.getVector());
        }
        Path vista = dir.resolve("vista-lamport.txt");
        Files.write(vista, linhas, StandardCharsets.UTF_8);
        System.out.println("=== Vista de Lamport ===");
        System.out.println("  " + linhas.size() + " registos ordenados por (L, k) em " + vista);

        // ---- 5. custo em bytes dos carimbos numa mensagem REG (endereço 127.0.0.1 e
        // porto 7000+k)
        if (!todos.isEmpty()) {
            long sem = 0, com = 0;
            for (Ev e : todos) {
                SensorRecord r = e.r;
                String base = "REG;127.0.0.1;" + (Wire.PORTO_BASE + e.k) + ";" + r.getSeq() + ";" + r.getSensorId()
                        + ";" + r.getTemperature() + ";" + r.getTimestamp();
                sem += base.getBytes(StandardCharsets.UTF_8).length;
                com += (base + ";" + r.getLamport() + ";" + r.getVector()).getBytes(StandardCharsets.UTF_8).length;
            }
            double ms = (double) sem / todos.size(), mc = (double) com / todos.size();
            System.out.println("=== Custo dos carimbos (mensagem REG) ===");
            System.out.println(String.format(
                    "  tamanho médio sem carimbos=%.1f B | com carimbos=%.1f B | acréscimo=%.1f B (%.0f %%)",
                    ms, mc, mc - ms, 100.0 * (mc - ms) / ms));
        }

        // ---- 6. (opcional) ordem de escrita numa réplica: falta alguma dependência
        // causal?
        if (args.length > 2) {
            tudoOk &= analisarReplica(Paths.get(args[2]), n);
        }
        System.out.println(tudoOk ? "RESULTADO: OK" : "RESULTADO: HÁ PROBLEMAS (ver acima)");
    }

    // Condição de entrega causal NA RÉPLICA: antes de escrever um registo de
    // partição k com vetor V, a réplica
    // já devia ter escrito, de cada OUTRA partição i que guarda, tudo até V[i]. Só
    // se avalia sobre as partições
    // que a réplica guarda (as outras componentes não são verificáveis localmente).
    static boolean analisarReplica(Path log, int n) throws IOException {
        System.out.println("=== Ordem de escrita na réplica (" + log.getFileName() + ") ===");
        List<String> linhas = Files.readAllLines(log, StandardCharsets.UTF_8);
        Set<Integer> detidas = new TreeSet<>();
        for (String l : linhas) {
            if (l.startsWith("Réplica") || l.startsWith("R?plica")) {
                Matcher m = CABECALHO.matcher(l);
                while (m.find())
                    detidas.add(Integer.parseInt(m.group(1)));
            }
        }
        long[] ultimo = new long[n];
        int total = 0, alarmes = 0, viol = 0, violAlarme = 0;
        List<String> ex = new ArrayList<>();
        List<Matcher> escritas = new ArrayList<>();
        for (String l : linhas) {
            Matcher m = ESCRITA.matcher(l);
            if (m.find()) {
                if (detidas.isEmpty() || !detidas.contains(Integer.parseInt(m.group(2))))
                    detidas.add(Integer.parseInt(m.group(2)));
                escritas.add(m);
            }
        }
        for (Matcher m : escritas) {
            long t = Long.parseLong(m.group(1));
            int k = Integer.parseInt(m.group(2));
            long seq = Long.parseLong(m.group(3));
            String sensor = m.group(4);
            VectorClock v;
            try {
                v = VectorClock.parse(m.group(6), n);
            } catch (IllegalArgumentException e) {
                continue;
            }
            total++;
            boolean alarme = sensor.startsWith("ALARM-");
            if (alarme)
                alarmes++;
            String falta = null;
            for (int i : detidas) {
                if (i != k && i < n && ultimo[i] < v.get(i)) {
                    falta = "p" + i + "#" + v.get(i) + " (a réplica só tinha escrito até p" + i + "#" + ultimo[i] + ")";
                    break;
                }
            }
            if (falta != null) {
                viol++;
                if (alarme)
                    violAlarme++;
                if (ex.size() < 6)
                    ex.add("t=" + t + " escreveu p" + k + "#" + seq + " " + sensor + " antes de " + falta);
            }
            ultimo[k] = Math.max(ultimo[k], seq);
        }
        System.out.println("  partições guardadas pela réplica: " + detidas);
        System.out.println("  registos escritos=" + total + " (alarmes=" + alarmes
                + ") | escritos ANTES de uma dependência causal="
                + viol + " (alarmes=" + violAlarme + ")");
        for (String e : ex)
            System.out.println("      " + e);
        if (total == 0)
            System.out.println("  (sem linhas ESCRITA: a réplica foi arrancada com -Dtempos=true?)");
        return true; // é um achado esperado (não há entrega causal), não uma falha do sistema
    }
}