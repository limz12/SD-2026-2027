package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Análise a posteriori da eleição (COMINDIR05).
 *
 * Uso: java [-Draft04.periodo=<ms>] [-Draft04.eleicaoMin=<ms>]
 *          [-Draft04.eleicaoMax=<ms>] -cp out raft04.RaftCheck <N> <pasta> [falhas.txt]
 *
 * Lê da pasta os ficheiros no-*, estado-*, hb-*, eventos-*, monitor-* e
 * produtor.txt e escreve um relatório que indica:
 *
 * - por partição: a sequência de termos, com os candidatos, os votos obtidos e
 *   o líder de cada termo (ou "voto dividido" para os termos sem líder) e a
 *   duração de cada eleição (do primeiro CANDIDATO ao LIDER);
 * - a segurança da eleição: nº de termos com mais de um LIDER (deve ser zero)
 *   e verificação de que o termo de cada nó nunca diminui;
 * - o tempo até ao primeiro líder de cada partição, desde o arranque do
 *   primeiro nó (evento ARRANQUE);
 * - com falhas.txt (linhas "p<k>;n<i>;<instante>"): a indisponibilidade de
 *   cada falha, desde a falha até ao primeiro LOTE de um líder de termo
 *   superior, e as leituras de produtor.txt enviadas nesse intervalo;
 * - as leituras perdidas: linhas de produtor.txt que não constam do registo
 *   final de nenhum nó da partição;
 * - os eventos TRUNCADO, com o nº de registos descartados e quantos destes
 *   existem, com outro seq, no registo final do líder;
 * - a coincidência final: para cada partição, os seq em que os ficheiros dos
 *   três nós diferem, até ao menor ultimoSeq entre eles.
 *
 * Todas as comparações de instantes entre ficheiros de processos diferentes só
 * são válidas porque TODOS os processos correram na MESMA máquina.
 */
public class RaftCheck {

    static class Ev {
        long t;
        String tipo;
        String det;
        int k;
        int no;

        String get(Pattern p) {
            Matcher m = p.matcher(det == null ? "" : det);
            return m.find() ? m.group(1) : null;
        }
    }

    static class HbL {
        long termo, hb, t, ultimoSeq, ultimoTermo;
        int n;
        long bytes;
        int no;
    }

    static class Termo {
        long termo;
        int k;
        TreeSet<Integer> candidatos = new TreeSet<>();
        TreeMap<Integer, List<Boolean>> votos = new TreeMap<>(); // candidato -> lista de concedido?
        TreeSet<Integer> nosLider = new TreeSet<>();
        long primeiroCandidato = -1;
        long tPrimeiroLider = -1;

        Termo(long termo) {
            this.termo = termo;
        }
    }

    static final Pattern TERMO_IGUAL = Pattern.compile("termo=(\\d+)");
    static final Pattern CANDIDATO = Pattern.compile("candidato=n(\\d+)");
    static final Pattern DE = Pattern.compile("de=(\\d+)");
    static final Pattern DESCARTADOS = Pattern.compile("descartados=(\\d+)");
    static final Pattern TERMOS_LISTA = Pattern.compile("termos=([^;]*)");
    static final Pattern LEITURAS = Pattern.compile("leituras=([^;]*)");

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String ms(long v) {
        return v + " ms (" + fmt(v / 1000.0) + " s)";
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Uso: RaftCheck <N> <pasta> [falhas.txt]");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[0]);
            if (n <= 0)
                throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.out.println("Erro: N deve ser um inteiro positivo.");
            return;
        }
        Path dir = Paths.get(args[1]);
        if (!Files.isDirectory(dir)) {
            System.out.println("Erro: pasta inválida: " + dir);
            return;
        }

        // ------------------------------------------------------------ leitura

        // eventos-p<k>-n<i>.txt
        Map<Integer, Map<Integer, List<Ev>>> eventos = new TreeMap<>(); // k -> no -> eventos
        try (Stream<Path> ls = Files.list(dir)) {
            for (Path p : ls.sorted().collect(Collectors.toList())) {
                Matcher m = Pattern.compile("eventos-p(\\d+)-n(\\d+)\\.txt").matcher(p.getFileName().toString());
                if (!m.matches())
                    continue;
                int k = Integer.parseInt(m.group(1));
                int no = Integer.parseInt(m.group(2));
                List<Ev> evs = new ArrayList<>();
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.isBlank())
                        continue;
                    String[] c = line.split(";", 4);
                    if (c.length < 3)
                        continue;
                    Ev e = new Ev();
                    e.t = Long.parseLong(c[0]);
                    e.tipo = c[1];
                    e.k = Integer.parseInt(c[2].substring(1));
                    e.det = c.length > 3 ? c[3] : "";
                    e.no = no;
                    evs.add(e);
                }
                evs.sort(Comparator.comparingLong(e -> e.t));
                eventos.computeIfAbsent(k, x -> new TreeMap<>()).put(no, evs);
            }
        }

        // hb-p<k>-n<i>.txt
        Map<Integer, List<HbL>> hbs = new TreeMap<>();
        try (Stream<Path> ls = Files.list(dir)) {
            for (Path p : ls.sorted().collect(Collectors.toList())) {
                Matcher m = Pattern.compile("hb-p(\\d+)-n(\\d+)\\.txt").matcher(p.getFileName().toString());
                if (!m.matches())
                    continue;
                int k = Integer.parseInt(m.group(1));
                int no = Integer.parseInt(m.group(2));
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.isBlank())
                        continue;
                    String[] c = line.trim().split(";");
                    HbL h = new HbL();
                    h.termo = Long.parseLong(c[0]);
                    h.hb = Long.parseLong(c[1]);
                    h.t = Long.parseLong(c[2]);
                    h.ultimoSeq = Long.parseLong(c[3]);
                    h.ultimoTermo = Long.parseLong(c[4]);
                    h.n = Integer.parseInt(c[5]);
                    h.bytes = Long.parseLong(c[6]);
                    h.no = no;
                    hbs.computeIfAbsent(k, x -> new ArrayList<>()).add(h);
                }
            }
        }
        for (List<HbL> l : hbs.values())
            l.sort(Comparator.comparingLong(h -> h.t));

        // no-p<k>-n<i>.txt (registo final de cada nó)
        Map<Integer, Map<Integer, List<SensorRecord>>> nos = new TreeMap<>();
        try (Stream<Path> ls = Files.list(dir)) {
            for (Path p : ls.sorted().collect(Collectors.toList())) {
                Matcher m = Pattern.compile("no-p(\\d+)-n(\\d+)\\.txt").matcher(p.getFileName().toString());
                if (!m.matches())
                    continue;
                int k = Integer.parseInt(m.group(1));
                int no = Integer.parseInt(m.group(2));
                List<SensorRecord> rs = new ArrayList<>();
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.isBlank())
                        continue;
                    try {
                        rs.add(SensorRecord.fromLine(line, n));
                    } catch (IllegalArgumentException e) {
                        System.out.println("REGISTO INVÁLIDO em " + p.getFileName() + ": " + e.getMessage());
                    }
                }
                nos.computeIfAbsent(k, x -> new TreeMap<>()).put(no, rs);
            }
        }

        // estado-p<k>-n<i>.txt
        Map<Integer, Map<Integer, long[]>> estados = new TreeMap<>();
        try (Stream<Path> ls = Files.list(dir)) {
            for (Path p : ls.sorted().collect(Collectors.toList())) {
                Matcher m = Pattern.compile("estado-p(\\d+)-n(\\d+)\\.txt").matcher(p.getFileName().toString());
                if (!m.matches())
                    continue;
                int k = Integer.parseInt(m.group(1));
                int no = Integer.parseInt(m.group(2));
                String[] f = Files.readString(p, StandardCharsets.UTF_8).trim().split(";");
                if (f.length == 2)
                    estados.computeIfAbsent(k, x -> new TreeMap<>())
                            .put(no, new long[] { Long.parseLong(f[0]), Long.parseLong(f[1]) });
            }
        }

        // monitor-*.txt
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
                    if (c.length < 3)
                        continue;
                    Ev e = new Ev();
                    e.t = Long.parseLong(c[0]);
                    e.tipo = c[1];
                    e.k = Integer.parseInt(c[2].substring(1));
                    e.det = c.length > 3 ? c[3] : "";
                    e.no = 0;
                    evs.add(e);
                }
                monitores.put(nome.substring("monitor-".length(), nome.length() - ".txt".length()), evs);
            }
        }

        // produtor.txt
        List<long[]> produtorLinhas = new ArrayList<>(); // {t, k}
        List<String[]> produtorLeituras = new ArrayList<>(); // {sensor, temp}
        Path fProd = dir.resolve("produtor.txt");
        if (Files.exists(fProd)) {
            for (String line : Files.readAllLines(fProd, StandardCharsets.UTF_8)) {
                if (line.isBlank())
                    continue;
                String[] c = line.trim().split(";");
                if (c.length != 4)
                    continue;
                produtorLinhas.add(new long[] { Long.parseLong(c[0]), Integer.parseInt(c[1].substring(1)) });
                produtorLeituras.add(new String[] { c[2], c[3] });
            }
        }

        // falhas.txt: linhas "p<k>;n<i>;<instante>"
        List<int[]> falhaKNo = new ArrayList<>();
        List<Long> falhaT = new ArrayList<>();
        if (args.length > 2) {
            Path fFalhas = Paths.get(args[2]);
            if (Files.exists(fFalhas)) {
                for (String line : Files.readAllLines(fFalhas, StandardCharsets.UTF_8)) {
                    if (line.isBlank())
                        continue;
                    String[] c = line.trim().split(";");
                    if (c.length != 3)
                        continue;
                    falhaKNo.add(new int[] { Integer.parseInt(c[0].substring(1)),
                            Integer.parseInt(c[1].substring(1)) });
                    falhaT.add(Long.parseLong(c[2]));
                }
            } else {
                System.out.println("AVISO: falhas.txt indicado não existe: " + fFalhas);
            }
        }

        // ------------------------------------------------------------ relatório

        System.out.println("====================================================================");
        System.out.println("RaftCheck · N = " + n + " · pasta: " + dir.toAbsolutePath());
        System.out.println("Δ = " + Wire.PERIODO_HB_MS + " ms | eleição em [" + Wire.ELEICAO_MIN_MS + ", "
                + Wire.ELEICAO_MAX_MS + "] ms | indisponibilidade prevista: ]"
                + Math.max(0, Wire.ELEICAO_MIN_MS - Wire.PERIODO_HB_MS) + ", "
                + (Wire.ELEICAO_MAX_MS + Wire.VERIFICACAO_MS) + "] ms");
        System.out.println("====================================================================");

        // termos por partição
        Map<Integer, TreeMap<Long, Termo>> termosPorParticao = new TreeMap<>();
        for (Map.Entry<Integer, Map<Integer, List<Ev>>> pk : eventos.entrySet()) {
            TreeMap<Long, Termo> termos = new TreeMap<>();
            for (List<Ev> evs : pk.getValue().values()) {
                for (Ev e : evs) {
                    if (e.tipo.equals("CANDIDATO")) {
                        String ts = e.get(TERMO_IGUAL);
                        if (ts == null)
                            continue;
                        Termo ti = termos.computeIfAbsent(Long.parseLong(ts), Termo::new);
                        ti.k = e.k;
                        ti.candidatos.add(e.no);
                        if (ti.primeiroCandidato == -1 || e.t < ti.primeiroCandidato)
                            ti.primeiroCandidato = e.t;
                    } else if (e.tipo.equals("VOTO")) {
                        String ts = e.get(TERMO_IGUAL);
                        String cs = e.get(CANDIDATO);
                        if (ts == null || cs == null)
                            continue;
                        Termo ti = termos.computeIfAbsent(Long.parseLong(ts), Termo::new);
                        ti.k = e.k;
                        ti.votos.computeIfAbsent(Integer.parseInt(cs), x -> new ArrayList<>())
                                .add(e.det.contains("concedido"));
                    } else if (e.tipo.equals("LIDER")) {
                        String ts = e.get(TERMO_IGUAL);
                        if (ts == null)
                            continue;
                        Termo ti = termos.computeIfAbsent(Long.parseLong(ts), Termo::new);
                        ti.k = e.k;
                        ti.nosLider.add(e.no);
                        if (ti.tPrimeiroLider == -1 || e.t < ti.tPrimeiroLider)
                            ti.tPrimeiroLider = e.t;
                    }
                }
            }
            termosPorParticao.put(pk.getKey(), termos);
        }

        // 1. sequência de termos por partição
        System.out.println();
        System.out.println("===== 1. Termos, candidatos, votos e líderes =====");
        for (int k = 0; k < n; k++) {
            System.out.println("--- p" + k + " ---");
            TreeMap<Long, Termo> termos = termosPorParticao.get(k);
            if (termos == null || termos.isEmpty()) {
                System.out.println("  sem eventos de eleição");
                continue;
            }
            for (Termo ti : termos.values()) {
                StringBuilder sb = new StringBuilder("  termo " + ti.termo + ": candidatos " + ti.candidatos
                        + " (o 1.º candidato inclui o auto-voto)");
                if (!ti.votos.isEmpty()) {
                    sb.append(" | votos concedidos:");
                    for (Map.Entry<Integer, List<Boolean>> v : ti.votos.entrySet()) {
                        long concedidos = v.getValue().stream().filter(b -> b).count();
                        sb.append(" n").append(v.getKey()).append("=").append(concedidos);
                        long recusados = v.getValue().size() - concedidos;
                        if (recusados > 0)
                            sb.append("(").append(recusados).append(" recusados)");
                    }
                } else {
                    sb.append(" | sem votos de outros nós");
                }
                if (!ti.nosLider.isEmpty()) {
                    long dur = ti.tPrimeiroLider - ti.primeiroCandidato;
                    sb.append(" -> LIDER n").append(ti.nosLider.first())
                            .append(" (eleição: ").append(dur).append(" ms)");
                    if (ti.nosLider.size() > 1)
                        sb.append("  *** E TAMBÉM n").append(ti.nosLider.tailSet(ti.nosLider.first() + 1))
                                .append(" — DOIS LÍDERES ***");
                } else {
                    sb.append(" -> VOTO DIVIDIDO (termo sem líder)");
                    Long seguinte = termos.higherKey(ti.termo);
                    if (seguinte != null) {
                        long dur = termos.get(seguinte).primeiroCandidato - ti.primeiroCandidato;
                        sb.append(" (durou ").append(dur).append(" ms até à eleição seguinte)");
                    } else {
                        sb.append(" (termo final sem líder)");
                    }
                }
                System.out.println(sb);
            }
        }

        // 2. segurança da eleição
        System.out.println();
        System.out.println("===== 2. Segurança da eleição =====");
        long termosComDoisLideres = 0;
        for (TreeMap<Long, Termo> termos : termosPorParticao.values())
            for (Termo ti : termos.values())
                if (ti.nosLider.size() > 1)
                    termosComDoisLideres++;
        System.out.println("  Termos com mais de um LIDER: " + termosComDoisLideres
                + (termosComDoisLideres == 0 ? " — OK (deve ser zero)" : " — VIOLAÇÃO da segurança"));
        long errosDoisLideres = 0;
        for (Map<Integer, List<Ev>> pm : eventos.values())
            for (List<Ev> evs : pm.values())
                for (Ev e : evs)
                    if (e.tipo.equals("ERRO") && e.det.contains("dois lideres"))
                        errosDoisLideres++;
        System.out.println("  Eventos ERRO 'dois lideres no termo': " + errosDoisLideres);

        System.out.println("  Termo de cada nó nunca diminui:");
        boolean termosOk = true;
        for (Map.Entry<Integer, Map<Integer, List<Ev>>> pk : eventos.entrySet()) {
            for (Map.Entry<Integer, List<Ev>> pn : pk.getValue().entrySet()) {
                long maxTermo = 0;
                for (Ev e : pn.getValue()) {
                    Long obs = null;
                    switch (e.tipo) {
                        case "CANDIDATO":
                        case "LIDER":
                        case "NOVO_LIDER":
                        case "VOTO":
                        case "DEMISSAO": {
                            String s = e.get(TERMO_IGUAL);
                            obs = s == null ? null : Long.parseLong(s);
                            break;
                        }
                        case "TERMO": {
                            Matcher m2 = Pattern.compile("\\d+->(\\d+)").matcher(e.det == null ? "" : e.det);
                            obs = m2.find() ? Long.parseLong(m2.group(1)) : null;
                            break;
                        }
                        default:
                            break;
                    }
                    if (obs != null) {
                        if (obs < maxTermo) {
                            termosOk = false;
                            System.out.println("    ERRO: p" + pk.getKey() + " n" + pn.getKey()
                                    + " observou termo " + obs + " < " + maxTermo + " em t=" + e.t
                                    + " (" + e.tipo + ")");
                        }
                        maxTermo = Math.max(maxTermo, obs);
                    }
                }
            }
        }
        System.out.println("    " + (termosOk ? "OK em todos os nós" : "HÁ VIOLAÇÕES (ver acima)"));

        // 3. tempo até ao primeiro líder
        System.out.println();
        System.out.println("===== 3. Tempo até ao primeiro líder (desde o arranque do primeiro nó) =====");
        for (int k = 0; k < n; k++) {
            Map<Integer, List<Ev>> pm = eventos.get(k);
            if (pm == null || pm.isEmpty()) {
                System.out.println("  p" + k + ": sem eventos");
                continue;
            }
            long t0 = Long.MAX_VALUE;
            for (List<Ev> evs : pm.values())
                if (!evs.isEmpty())
                    t0 = Math.min(t0, evs.get(0).t); // a primeira linha é o ARRANQUE
            long tLider = Long.MAX_VALUE;
            int noLider = -1;
            for (List<Ev> evs : pm.values())
                for (Ev e : evs)
                    if (e.tipo.equals("LIDER") && e.t < tLider) {
                        tLider = e.t;
                        noLider = e.no;
                    }
            if (tLider == Long.MAX_VALUE) {
                System.out.println("  p" + k + ": SEM LÍDER em todo o registo");
            } else {
                System.out.println("  p" + k + ": " + ms(tLider - t0) + " (n" + noLider + "; arranque t=" + t0
                        + ", LIDER t=" + tLider + ")");
            }
        }

        // 4. indisponibilidade por falha
        if (!falhaT.isEmpty()) {
            System.out.println();
            System.out.println("===== 4. Indisponibilidade por falha (falha -> 1.º LOTE de termo superior) =====");
            for (int f = 0; f < falhaT.size(); f++) {
                int k = falhaKNo.get(f)[0];
                int no = falhaKNo.get(f)[1];
                long tf = falhaT.get(f);
                long termoNaFalha = 0;
                for (HbL h : hbs.getOrDefault(k, Collections.emptyList()))
                    if (h.t <= tf)
                        termoNaFalha = Math.max(termoNaFalha, h.termo);
                HbL primeiro = null;
                for (HbL h : hbs.getOrDefault(k, Collections.emptyList()))
                    if (h.t > tf && h.termo > termoNaFalha && (primeiro == null || h.t < primeiro.t))
                        primeiro = h;
                System.out.print("  falha " + (f + 1) + ": p" + k + " n" + no + " em t=" + tf
                        + " (termo do líder = " + termoNaFalha + ")");
                if (primeiro == null) {
                    System.out.println(" -> sem LOTE de termo superior até ao fim do registo"
                            + " (a partição ficou sem maioria?)");
                    continue;
                }
                System.out.println(" -> novo líder n" + primeiro.no + " (termo " + primeiro.termo + ", hb="
                        + primeiro.hb + ", t=" + primeiro.t + ") -> INDISPONIBILIDADE " + ms(primeiro.t - tf));
                List<String> noIntervalo = new ArrayList<>();
                for (int j = 0; j < produtorLinhas.size(); j++) {
                    long[] pl = produtorLinhas.get(j);
                    if (pl[1] == k && pl[0] >= tf && pl[0] <= primeiro.t)
                        noIntervalo.add(produtorLeituras.get(j)[0] + " " + produtorLeituras.get(j)[1]
                                + " (t=" + pl[0] + ")");
                }
                System.out.println("     leituras enviadas para p" + k + " no intervalo: "
                        + (noIntervalo.isEmpty() ? "nenhuma"
                                : noIntervalo.size() + " -> " + String.join(", ", noIntervalo)));
            }
        }

        // 5. leituras perdidas
        System.out.println();
        System.out.println("===== 5. Leituras perdidas (não constam do registo final de nenhum nó) =====");
        long totalPerdidas = 0;
        for (int k = 0; k < n; k++) {
            Map<String, Long> enviadas = new TreeMap<>();
            long enviadasTotal = 0;
            for (int j = 0; j < produtorLinhas.size(); j++)
                if (produtorLinhas.get(j)[1] == k) {
                    enviadas.merge(produtorLeituras.get(j)[0] + ";" + produtorLeituras.get(j)[1], 1L, Long::sum);
                    enviadasTotal++;
                }
            Map<String, Long> registadas = new TreeMap<>();
            Map<Integer, List<SensorRecord>> pn = nos.get(k);
            Set<String> paresVistos = new HashSet<>(); // deduplica o mesmo registo copiado nos três nós
            if (pn != null)
                for (List<SensorRecord> rs : pn.values())
                    for (SensorRecord r : rs) {
                        if (!paresVistos.add(r.getSeq() + ";" + r.getTerm()))
                            continue; // mesmo (seq, termo) em outro nó: é o MESMO registo
                        if (!r.getSensorId().startsWith("ALARM-"))
                            registadas.merge(r.getSensorId() + ";" + r.getTemperature(), 1L, Long::sum);
                    }
            List<String> perdidas = new ArrayList<>();
            long conta = 0;
            for (Map.Entry<String, Long> e : enviadas.entrySet()) {
                long falta = e.getValue() - registadas.getOrDefault(e.getKey(), 0L);
                if (falta > 0) {
                    conta += falta;
                    String[] sv = e.getKey().split(";");
                    perdidas.add(sv[0] + " " + sv[1] + " ×" + falta);
                }
            }
            totalPerdidas += conta;
            System.out.println("  p" + k + ": " + enviadasTotal + " leituras enviadas, " + conta + " perdidas"
                    + (perdidas.isEmpty() ? "" : " -> " + String.join(", ", perdidas)));
        }
        System.out.println("  TOTAL de leituras perdidas: " + totalPerdidas);

        // 6. eventos TRUNCADO
        System.out.println();
        System.out.println("===== 6. Truncagens (divergências resolvidas a partir do líder) =====");
        long totalTrunc = 0;
        for (Map.Entry<Integer, Map<Integer, List<Ev>>> pk : eventos.entrySet()) {
            // líder final da partição: o último evento LIDER
            int liderFinal = -1;
            long tLider = -1;
            for (List<Ev> evs : pk.getValue().values())
                for (Ev e2 : evs)
                    if (e2.tipo.equals("LIDER") && e2.t >= tLider) {
                        tLider = e2.t;
                        liderFinal = e2.no;
                    }
            for (Map.Entry<Integer, List<Ev>> pn : pk.getValue().entrySet()) {
                for (Ev e : pn.getValue()) {
                    if (!e.tipo.equals("TRUNCADO"))
                        continue;
                    totalTrunc++;
                    String de = e.get(DE);
                    String desc = e.get(DESCARTADOS);
                    String termos = e.get(TERMOS_LISTA);
                    String leituras = e.get(LEITURAS);
                    int nDesc = desc == null ? 0 : Integer.parseInt(desc);
                    long comOutroSeq = 0;
                    List<String> leiturasDescartadas = new ArrayList<>();
                    if (leituras != null && !leituras.isBlank()) {
                        Map<Integer, List<SensorRecord>> pf = nos.get(pk.getKey());
                        Set<String> doLider = new HashSet<>();
                        if (pf != null && pf.containsKey(liderFinal))
                            for (SensorRecord r : pf.get(liderFinal))
                                doLider.add(r.getSensorId() + ";" + r.getTemperature());
                        for (String l : leituras.split(",")) {
                            leiturasDescartadas.add(l.replace(':', ' '));
                            if (doLider.contains(l))
                                comOutroSeq++;
                        }
                    }
                    System.out.println("  p" + pk.getKey() + " n" + pn.getKey() + ": de=seq " + de
                            + ", descartados=" + nDesc + " (termos " + termos + ")"
                            + (leiturasDescartadas.isEmpty() ? ""
                                    : ", leituras: " + String.join(", ", leiturasDescartadas))
                            + " | existem com outro seq no registo final do líder (n" + liderFinal + "): "
                            + comOutroSeq);
                }
            }
        }
        System.out.println("  TOTAL de truncagens: " + totalTrunc);

        // 7. coincidência final
        System.out.println();
        System.out.println("===== 7. Coincidência final dos nós =====");
        long divergentesTotal = 0;
        for (int k = 0; k < n; k++) {
            Map<Integer, List<SensorRecord>> pn = nos.get(k);
            if (pn == null || pn.isEmpty()) {
                System.out.println("  p" + k + ": sem ficheiros no-*");
                continue;
            }
            Map<Integer, TreeMap<Long, String>> porNo = new TreeMap<>();
            Map<Integer, Long> ultimoSeq = new TreeMap<>();
            for (Map.Entry<Integer, List<SensorRecord>> e : pn.entrySet()) {
                TreeMap<Long, String> m = new TreeMap<>();
                for (SensorRecord r : e.getValue())
                    m.put(r.getSeq(), r.toLine());
                porNo.put(e.getKey(), m);
                ultimoSeq.put(e.getKey(), m.isEmpty() ? 0L : m.lastKey());
            }
            long minUltimo = Collections.min(ultimoSeq.values());
            List<Long> divergentes = new ArrayList<>();
            for (long s = 1; s <= minUltimo; s++) {
                String ref = null;
                for (TreeMap<Long, String> m : porNo.values()) {
                    String linha = m.get(s);
                    if (ref == null)
                        ref = linha;
                    else if (!Objects.equals(ref, linha)) {
                        divergentes.add(s);
                        break;
                    }
                }
            }
            divergentesTotal += divergentes.size();
            System.out.println("  p" + k + ": último seq por nó " + ultimoSeq + " | coincidem até seq=" + minUltimo
                    + (divergentes.isEmpty() ? " — OK" : " — DIVERGÊNCIAS nos seq " + divergentes));
        }
        System.out.println("  TOTAL de seq divergentes: " + divergentesTotal);

        // 8. estado persistente final
        System.out.println();
        System.out.println("===== 8. Estado persistente final (estado-p<k>-n<i>.txt: termo;votouEm) =====");
        for (int k = 0; k < n; k++) {
            Map<Integer, long[]> pe = estados.get(k);
            if (pe == null) {
                System.out.println("  p" + k + ": sem ficheiros estado-*");
                continue;
            }
            StringBuilder sb = new StringBuilder("  p" + k + ":");
            for (Map.Entry<Integer, long[]> e : pe.entrySet())
                sb.append(" n").append(e.getKey()).append("(termo=").append(e.getValue()[0])
                        .append(", votouEm=").append(e.getValue()[1] == 0 ? "-" : "n" + e.getValue()[1])
                        .append(")");
            System.out.println(sb);
        }

        // 9. monitores
        System.out.println();
        System.out.println("===== 9. Monitores (produtor / processador) =====");
        for (Map.Entry<String, List<Ev>> pm : monitores.entrySet()) {
            Map<String, Long> contagens = new TreeMap<>();
            for (Ev e : pm.getValue())
                contagens.merge(e.tipo, 1L, Long::sum);
            System.out.println("  " + pm.getKey() + ": " + contagens);
        }

        System.out.println();
        System.out.println("====================================================================");
        System.out.println("FIM do RaftCheck");
        System.out.println("====================================================================");
    }
}
