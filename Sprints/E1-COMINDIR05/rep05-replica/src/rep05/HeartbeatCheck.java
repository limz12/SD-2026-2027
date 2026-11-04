package rep05;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Análise a posteriori do detetor de falhas e do envio em lote.
 *
 * Uso: java [-Drep04.periodo=<ms>] [-Drep04.fator=<n>] -cp out
 * rep04.HeartbeatCheck <N> <pasta> [falhas.txt]
 * <pasta> contém hb-p<k>.txt (líderes), lider-p<k>.txt (líderes) e
 * monitor-*.txt (réplicas, processador, produtor).
 * falhas.txt (opcional): linhas "p<k>;<instante em ms>" anotadas no momento de
 * cada falha provocada.
 * Os -D devem ser os usados nos processos: servem só para escrever o intervalo
 * previsto do tempo de deteção.
 *
 * Todas as comparações de instantes entre ficheiros de processos diferentes só
 * são válidas porque TODOS os
 * processos correram na MESMA máquina (relógio físico comum). O detetor, em
 * contrapartida, só usa
 * System.nanoTime() local e não depende disso.
 */
public class HeartbeatCheck {
    static class Hb {
        long inc, hb, t, ultimoSeq;
        int n, bytes;
    }

    static class Ev {
        long t;
        String tipo;
        int k;
        String det;

        String get(Pattern p) {
            Matcher m = p.matcher(det);
            return m.find() ? m.group(1) : null;
        }
    }

    static final Pattern INC = Pattern.compile("inc=(\\d+)");
    static final Pattern SILENCIO = Pattern.compile("silencio_ms=(\\d+)");
    static final Pattern FAIXA = Pattern.compile("seq=(\\d+)\\.\\.(\\d+)");

    static long epoch(String ts) {
        return LocalDateTime.parse(ts).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Uso: HeartbeatCheck <N> <pasta> [falhas.txt]");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        long delta = Wire.PERIODO_HB_MS, timeout = delta * Wire.FATOR_TIMEOUT;
        long lo = timeout - delta, hi = timeout + Wire.VERIFICACAO_MS;

        // ---------------------------------------------------------------- leitura
        List<List<Hb>> hbs = new ArrayList<>();
        List<Map<Long, Long>> instanteDe = new ArrayList<>(); // por partição: seq -> instante do registo (ms)
        List<List<SensorRecord>> regs = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            List<Hb> l = new ArrayList<>();
            Path f = dir.resolve("hb-p" + k + ".txt");
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank())
                        continue;
                    String[] c = line.trim().split(";");
                    Hb h = new Hb();
                    h.inc = Long.parseLong(c[0]);
                    h.hb = Long.parseLong(c[1]);
                    h.t = Long.parseLong(c[2]);
                    h.ultimoSeq = Long.parseLong(c[3]);
                    h.n = Integer.parseInt(c[4]);
                    h.bytes = Integer.parseInt(c[5]);
                    l.add(h);
                }
            }
            hbs.add(l);
            Map<Long, Long> m = new HashMap<>();
            List<SensorRecord> rs = new ArrayList<>();
            Path fl = dir.resolve("lider-p" + k + ".txt");
            if (Files.exists(fl)) {
                for (String line : Files.readAllLines(fl, StandardCharsets.UTF_8)) {
                    if (line.isBlank())
                        continue;
                    SensorRecord r = SensorRecord.fromLine(line);
                    rs.add(r);
                    m.put(r.getSeq(), epoch(r.getTimestamp()));
                }
            }
            instanteDe.add(m);
            regs.add(rs);
        }
        Map<String, List<Ev>> monitores = new TreeMap<>();
        try (Stream<Path> ls = Files.list(dir)) {
            for (Path p : ls.sorted().collect(Collectors.toList())) {
                String nome = p.getFileName().toString();
                if (!nome.startsWith("monitor-") || !nome.endsWith(".txt"))
                    continue;
                List<Ev> evs = new ArrayList<>();
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.isBlank())
                        continue;
                    String[] c = line.split(";", 4);
                    Ev e = new Ev();
                    e.t = Long.parseLong(c[0]);
                    e.tipo = c[1];
                    e.k = Integer.parseInt(c[2].substring(1));
                    e.det = c.length > 3 ? c[3] : "";
                    evs.add(e);
                }
                monitores.put(nome.substring("monitor-".length(), nome.length() - ".txt".length()), evs);
            }
        }

        // ---------------------------------------------------------------- 1.
        // heartbeats
        System.out.println("=== 1. Heartbeats e lotes (por partição e incarnação) ===");
        long totalLotes = 0, totalRegistos = 0;
        for (int k = 0; k < n; k++) {
            Map<Long, List<Hb>> porInc = new TreeMap<>();
            for (Hb h : hbs.get(k))
                porInc.computeIfAbsent(h.inc, x -> new ArrayList<>()).add(h);
            if (porInc.isEmpty())
                System.out.println("  p" + k + ": sem hb-p" + k + ".txt");
            for (Map.Entry<Long, List<Hb>> e : porInc.entrySet()) {
                List<Hb> l = e.getValue();
                l.sort(Comparator.comparingLong(h -> h.hb));
                long vazios = l.stream().filter(h -> h.n == 0).count();
                long soma = l.stream().mapToLong(h -> h.n).sum();
                int max = l.stream().mapToInt(h -> h.n).max().orElse(0);
                double somaInt = 0;
                long maxInt = 0;
                int nInt = 0;
                for (int i = 1; i < l.size(); i++) {
                    long d = l.get(i).t - l.get(i - 1).t;
                    somaInt += d;
                    maxInt = Math.max(maxInt, d);
                    nInt++;
                }
                totalLotes += l.size();
                totalRegistos += soma;
                System.out.println("  p" + k + " inc=" + e.getKey() + ": lotes=" + l.size() + " | vazios=" + vazios
                        + " (" + fmt(100.0 * vazios / l.size()) + " %) | registos/lote média="
                        + fmt((double) soma / l.size())
                        + " máx=" + max + " | intervalo entre heartbeats média="
                        + (nInt > 0 ? fmt(somaInt / nInt) : "-")
                        + " ms máx=" + (nInt > 0 ? maxInt : "-") + " ms");
            }
        }

        // ---------------------------------------------------------------- 2. suspeitas
        System.out.println(
                "=== 2. Suspeitas por participante e partição (Δ=" + delta + " ms, timeout=" + timeout + " ms) ===");
        int reais = 0, falsas = 0, indeterminadas = 0;
        List<long[]> anotadas = new ArrayList<>(); // {k, instante} lidas de falhas.txt (se existir)
        if (args.length > 2) {
            for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
                if (line.isBlank())
                    continue;
                String[] c = line.trim().split(";");
                anotadas.add(new long[] { Long.parseLong(c[0].substring(1)), Long.parseLong(c[1]) });
            }
        }
        Map<String, List<long[]>> suspeitasPorChave = new HashMap<>(); // "participante|k" -> {tSuspeita}
        for (Map.Entry<String, List<Ev>> pe : monitores.entrySet()) {
            for (int k = 0; k < n; k++) {
                String lastInc = null;
                long tSusp = -1;
                String incSusp = null;
                String silMon = null;
                boolean aberta = false;
                for (Ev e : pe.getValue()) {
                    if (e.k != k)
                        continue;
                    if (e.tipo.equals("RECEBIDO"))
                        lastInc = e.get(INC);
                    if (e.tipo.equals("SUSPEITA")) {
                        aberta = true;
                        tSusp = e.t;
                        incSusp = lastInc;
                        silMon = e.get(SILENCIO);
                        suspeitasPorChave.computeIfAbsent(pe.getKey() + "|" + k, x -> new ArrayList<>())
                                .add(new long[] { e.t });
                    }
                    if (e.tipo.equals("RETOMA") && aberta) {
                        String incRet = e.get(INC);
                        boolean falsa = incRet != null && incRet.equals(incSusp);
                        String classe;
                        if (incSusp == null) {
                            indeterminadas++;
                            classe = "INDETERMINADA (nenhum heartbeat recebido antes; RETOMA com inc=" + incRet + ")";
                        } else if (falsa) {
                            falsas++;
                            classe = "FALSA SUSPEITA (RETOMA com o mesmo inc, " + (e.t - tSusp) + " ms depois)";
                        } else {
                            reais++;
                            classe = "FALHA REAL (o líder reiniciou: inc " + incSusp + " -> " + incRet + ")";
                        }
                        relatarSuspeita(pe.getKey(), k, tSusp, silMon, incSusp, hbs.get(k),
                                classe + anotacao(anotadas, args.length > 2, k, tSusp));
                        aberta = false;
                    }
                }
                if (aberta) {
                    String classe;
                    if (incSusp == null) {
                        indeterminadas++;
                        classe = "INDETERMINADA (nenhum heartbeat recebido antes e nenhum depois)";
                    } else {
                        reais++;
                        classe = "FALHA REAL (sem novo heartbeat até ao fim do registo)";
                    }
                    relatarSuspeita(pe.getKey(), k, tSusp, silMon, incSusp, hbs.get(k),
                            classe + anotacao(anotadas, args.length > 2, k, tSusp));
                }
            }
        }
        System.out.println("  TOTAL: falhas reais=" + reais + " | falsas suspeitas=" + falsas
                + (indeterminadas > 0 ? " | indeterminadas=" + indeterminadas : ""));

        // ---------------------------------------------------------------- 3. tempo de
        // deteção
        if (args.length > 2) {
            System.out.println("=== 3. Tempo de deteção (previsto: ]" + lo + ", " + hi + "] ms) ===");
            List<String[]> falhas = new ArrayList<>();
            for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
                if (line.isBlank())
                    continue;
                String[] c = line.trim().split(";");
                falhas.add(c);
            }
            List<Long> todas = new ArrayList<>();
            for (int i = 0; i < falhas.size(); i++) {
                int k = Integer.parseInt(falhas.get(i)[0].substring(1));
                long tf = Long.parseLong(falhas.get(i)[1]);
                long proxima = Long.MAX_VALUE;
                for (int j = i + 1; j < falhas.size(); j++) {
                    if (Integer.parseInt(falhas.get(j)[0].substring(1)) == k) {
                        proxima = Long.parseLong(falhas.get(j)[1]);
                        break;
                    }
                }
                StringBuilder sb = new StringBuilder("  falha " + (i + 1) + " em p" + k + " (t=" + tf + "):");
                for (Map.Entry<String, List<Ev>> pe : monitores.entrySet()) {
                    Long det = null;
                    for (Ev e : pe.getValue()) {
                        if (e.k == k && e.tipo.equals("SUSPEITA") && e.t >= tf && e.t < proxima) {
                            det = e.t - tf;
                            break;
                        }
                    }
                    if (det == null) {
                        sb.append(" ").append(pe.getKey()).append("=sem suspeita");
                        continue;
                    }
                    todas.add(det);
                    sb.append(" ").append(pe.getKey()).append("=").append(det).append(" ms")
                            .append(det > lo && det <= hi ? "" : "(FORA do previsto)");
                }
                System.out.println(sb);
            }
            if (!todas.isEmpty()) {
                System.out.println("  tempo de deteção (todos os participantes e falhas): mín=" + Collections.min(todas)
                        + " ms | média=" + fmt(todas.stream().mapToLong(x -> x).average().orElse(0)) + " ms | máx="
                        + Collections.max(todas) + " ms | amostras=" + todas.size());
            }
        }

        // ---------------------------------------------------------------- 4. latência
        // de lote
        System.out.println("=== 4. Latência de lote (RECEBIDO que contém o registo − instante do registo) ===");
        for (int k = 0; k < n; k++) {
            List<Long> lat = new ArrayList<>();
            StringBuilder porPart = new StringBuilder();
            for (Map.Entry<String, List<Ev>> pe : monitores.entrySet()) {
                List<Long> mine = new ArrayList<>();
                for (Ev e : pe.getValue()) {
                    if (e.k != k || !e.tipo.equals("RECEBIDO"))
                        continue;
                    Matcher m = FAIXA.matcher(e.det);
                    if (!m.find())
                        continue;
                    for (long s = Long.parseLong(m.group(1)); s <= Long.parseLong(m.group(2)); s++) {
                        Long ti = instanteDe.get(k).get(s);
                        if (ti != null)
                            mine.add(e.t - ti);
                    }
                }
                if (!mine.isEmpty()) {
                    porPart.append(" ").append(pe.getKey()).append("=")
                            .append(fmt(mine.stream().mapToLong(x -> x).average().orElse(0)));
                    lat.addAll(mine);
                }
            }
            if (lat.isEmpty()) {
                System.out.println("  p" + k + ": sem lotes com registos");
                continue;
            }
            System.out.println("  p" + k + ": amostras=" + lat.size() + " | média="
                    + fmt(lat.stream().mapToLong(x -> x).average().orElse(0))
                    + " ms | máx=" + Collections.max(lat) + " ms | médias por participante:" + porPart);
        }
        System.out.println("  (Δ/2 = " + delta / 2 + " ms é a latência média esperada com leituras uniformes)");

        // ---------------------------------------------------------------- 5. eventos
        System.out.println("=== 5. HB_PERDIDO e RECUPERACAO ===");
        int hbPerdidos = 0, recuperacoes = 0;
        for (Map.Entry<String, List<Ev>> pe : monitores.entrySet()) {
            int a = 0, b = 0;
            for (Ev e : pe.getValue()) {
                if (e.tipo.equals("HB_PERDIDO"))
                    a++;
                if (e.tipo.equals("RECUPERACAO"))
                    b++;
            }
            hbPerdidos += a;
            recuperacoes += b;
            System.out.println("  " + pe.getKey() + ": HB_PERDIDO=" + a + " | RECUPERACAO=" + b);
        }
        System.out.println("  TOTAL: HB_PERDIDO=" + hbPerdidos + " | RECUPERACAO=" + recuperacoes);

        // ---------------------------------------------------------------- 6. custo em
        // bytes e mensagens
        System.out.println("=== 6. Custo do envio em lote ===");
        long bytesLotesComRegistos = 0, regsEmLotes = 0, bytesVazios = 0, vazios = 0, bytesTodos = 0;
        for (List<Hb> l : hbs) {
            for (Hb h : l) {
                bytesTodos += h.bytes;
                if (h.n > 0) {
                    bytesLotesComRegistos += h.bytes;
                    regsEmLotes += h.n;
                } else {
                    bytesVazios += h.bytes;
                    vazios++;
                }
            }
        }
        long bytesReg = 0, nReg = 0;
        for (int k = 0; k < n; k++) {
            for (SensorRecord r : regs.get(k)) {
                bytesReg += ("REG;127.0.0.1;" + (Wire.PORTO_BASE + k) + ";" + r.toLine())
                        .getBytes(StandardCharsets.UTF_8).length;
                nReg++;
            }
        }
        if (regsEmLotes > 0) {
            System.out.println("  bytes por registo em LOTE (cabeçalho partilhado, só lotes com registos)="
                    + fmt((double) bytesLotesComRegistos / regsEmLotes)
                    + " B | REG individual (COMINDIR03, 127.0.0.1)=" + fmt((double) bytesReg / Math.max(1, nReg))
                    + " B");
            System.out.println(
                    "  incluindo os heartbeats vazios: " + fmt((double) bytesTodos / regsEmLotes) + " B por registo ("
                            + vazios + " lotes vazios de " + (vazios > 0 ? fmt((double) bytesVazios / vazios) : "-")
                            + " B cada)");
        } else {
            System.out.println("  sem lotes com registos");
        }
        System.out.println(
                "  mensagens no tópico de dados: REG (COMINDIR03)=" + nReg + " | LOTE (COMINDIR04)=" + totalLotes
                        + (nReg > 0 ? " (" + fmt(100.0 * totalLotes / nReg) + " % do nº de REG)" : ""));
    }

    // com falhas.txt: a suspeita corresponde a uma falha provocada (a mais recente
    // da partição, anterior à suspeita)?
    static String anotacao(List<long[]> anotadas, boolean temFicheiro, int k, long t) {
        if (!temFicheiro)
            return "";
        for (long[] a : anotadas) {
            if (a[0] == k && a[1] <= t && t - a[1] < 120_000)
                return " [falha anotada em t=" + a[1] + "]";
        }
        return " [SEM falha anotada: o líder estava vivo]";
    }

    static void relatarSuspeita(String participante, int k, long t, String silMon, String incSusp, List<Hb> hbs,
            String classe) {
        Long ultimoHb = null;
        for (Hb h : hbs) {
            if ((incSusp == null || incSusp.equals(String.valueOf(h.inc))) && h.t <= t) {
                if (ultimoHb == null || h.t > ultimoHb)
                    ultimoHb = h.t;
            }
        }
        System.out.println(
                "  " + participante + " p" + k + ": SUSPEITA t=" + t + " | silêncio medido pelo detetor=" + silMon
                        + " ms | desde o último heartbeat publicado="
                        + (ultimoHb == null ? "?" : (t - ultimoHb) + " ms") + " -> " + classe);
    }
}