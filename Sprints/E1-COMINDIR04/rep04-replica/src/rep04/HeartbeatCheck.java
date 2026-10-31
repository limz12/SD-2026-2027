package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verificação do funcionamento dos batimentos (COMINDIR04).
 *
 * Execução:
 * HeartbeatCheck <N> <pasta> [falhas.txt]
 *
 * Lê da pasta:
 * - hb-p<k>.txt       (inc;hb;instante;ultimoSeq;n;bytes)
 * - lider-p<k>.txt    (seq;sensor;temp;timestamp;L;V)
 * - monitor-*.txt     (instante;EVENTO;p<k>;detalhes...)
 * - falhas.txt        (p<k>;<instante_ms>), opcional
 */
public class HeartbeatCheck {

    /** Uma entrada do registo de heartbeats do líder. */
    static class Hb {
        long inc;
        long hb;
        long instante;
        long ultimoSeq;
        int n;
        long bytes;
    }

    /** Um evento lido de um ficheiro monitor-*.txt. */
    static class Evento {
        String participante;
        long instante;
        String tipo;       // RECEBIDO, SUSPEITA, RETOMA, REINICIO, HB_PERDIDO, RECUPERACAO, APLICADO
        int k;
        String detalhes;
    }

    /** Uma suspeita e a sua classificação. */
    static class Suspeita {
        String participante;
        int k;
        long instante;
        long silencioMs;
        boolean falsa;
        long instanteUltimoHb;
        Long deteccaoMs;   // preenchido quando existe entrada em falhas.txt
    }

    public static void main(String[] args) {

        if (args.length < 2 || args.length > 3) {
            System.out.println("Uso: HeartbeatCheck <N> <pasta> [falhas.txt]");
            return;
        }

        int N;
        try {
            N = Integer.parseInt(args[0]);
            if (N <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.out.println("Erro: N deve ser um inteiro positivo.");
            return;
        }

        Path pasta = Paths.get(args[1]);

        if (!Files.isDirectory(pasta)) {
            System.out.println("Erro: pasta inválida: " + pasta);
            return;
        }

        Path ficheiroFalhas = args.length > 2
                ? Paths.get(args[2])
                : null;

        Locale loc = Locale.US;

        System.out.println("========== HEARTBEAT CHECK ==========");
        System.out.println("Número de partições: " + N);
        System.out.println("Pasta: " + pasta.toAbsolutePath());

        // ----------------------------------------------------------------
        // 1. Registos de heartbeat dos líderes
        // ----------------------------------------------------------------
        List<List<Hb>> hbs = new ArrayList<>();

        for (int k = 0; k < N; k++) {
            List<Hb> listaK = new ArrayList<>();
            hbs.add(listaK);
            Path caminho = pasta.resolve("hb-p" + k + ".txt");
            if (!Files.isRegularFile(caminho)) {
                System.out.println("AVISO: ficheiro ausente: " + caminho.getFileName());
                continue;
            }
            int numeroLinha = 0;
            for (String linha : lerLinhas(caminho)) {
                numeroLinha++;
                if (linha.isBlank()) continue;
                String[] f = linha.trim().split(";", -1);
                if (f.length != 6) {
                    System.out.println("AVISO: linha inválida em hb-p" + k + ".txt:" + numeroLinha);
                    continue;
                }
                try {
                    Hb e = new Hb();
                    e.inc = Long.parseLong(f[0]);
                    e.hb = Long.parseLong(f[1]);
                    e.instante = Long.parseLong(f[2]);
                    e.ultimoSeq = Long.parseLong(f[3]);
                    e.n = Integer.parseInt(f[4]);
                    e.bytes = Long.parseLong(f[5]);
                    listaK.add(e);
                } catch (NumberFormatException ex) {
                    System.out.println("AVISO: linha inválida em hb-p" + k + ".txt:" + numeroLinha);
                }
            }
        }

        System.out.println();
        System.out.println("----- LOTES PUBLICADOS (hb-p<k>.txt) -----");

        for (int k = 0; k < N; k++) {

            // Agrupar por encarnação preservando a ordem
            Map<Long, List<Hb>> porInc = new LinkedHashMap<>();
            for (Hb e : hbs.get(k)) {
                porInc.computeIfAbsent(e.inc, x -> new ArrayList<>()).add(e);
            }

            System.out.println();
            System.out.println("Partição p" + k + ": " + hbs.get(k).size() + " lotes em " + porInc.size() + " encarnação(ões)");

            for (Map.Entry<Long, List<Hb>> grupo : porInc.entrySet()) {
                List<Hb> lista = grupo.getValue();
                long vazios = lista.stream().filter(e -> e.n == 0).count();
                double pcVazios = lista.isEmpty() ? 0 : 100.0 * vazios / lista.size();

                long somaN = 0;
                int maxN = 0;
                for (Hb e : lista) {
                    somaN += e.n;
                    if (e.n > maxN) maxN = e.n;
                }
                double mediaN = (double) somaN / lista.size();

                // Intervalos entre heartbeats consecutivos da mesma encarnação
                List<Long> intervalos = new ArrayList<>();
                for (int i = 1; i < lista.size(); i++) {
                    intervalos.add(lista.get(i).instante - lista.get(i - 1).instante);
                }
                Long minIv = intervalos.isEmpty() ? null : intervalos.stream().min(Long::compare).get();
                Long maxIv = intervalos.isEmpty() ? null : intervalos.stream().max(Long::compare).get();
                double mediaIv = intervalos.stream().mapToLong(Long::longValue).average().orElse(0);

                System.out.printf(loc,
                    "  inc=%d: lotes=%d vazios=%d (%.1f%%) "
                    + "registos/lote média=%.2f máx=%d "
                    + "intervalo média=%.0f ms máx=%d ms%n",
                    grupo.getKey(), lista.size(), vazios, pcVazios,
                    mediaN, maxN, mediaIv,
                    maxIv == null ? 0 : maxIv);

                if (minIv != null && minIv <= 0) {
                    System.out.println("  AVISO: intervalo não positivo (" + minIv + " ms) — relógio físico recuou ou eventos com o mesmo instante");
                }
            }
        }

        // ----------------------------------------------------------------
        // 2. Eventos dos participantes (monitor-*.txt)
        // ----------------------------------------------------------------
        Map<String, List<Evento>> participantes = new LinkedHashMap<>();

        try (var stream = Files.list(pasta)) {
            List<Path> ficheiros = stream
                    .filter(p -> {
                        String nome = p.getFileName().toString();
                        return nome.startsWith("monitor-") && nome.endsWith(".txt");
                    })
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());

            for (Path f : ficheiros) {
                List<Evento> lista = new ArrayList<>();
                for (String linha : lerLinhas(f)) {
                    if (linha.isBlank()) continue;
                    String[] partes = linha.trim().split(";", 4);
                    if (partes.length < 3) continue;
                    Evento e = new Evento();
                    e.participante = f.getFileName().toString();
                    try {
                        e.instante = Long.parseLong(partes[0]);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    e.tipo = partes[1];
                    String alvo = partes[2];
                    if (alvo.startsWith("p") && alvo.length() > 1) {
                        try {
                            e.k = Integer.parseInt(alvo.substring(1));
                        } catch (NumberFormatException ex) {
                            continue;
                        }
                    } else {
                        continue;
                    }
                    e.detalhes = partes.length > 3 ? partes[3] : "";
                    lista.add(e);
                }
                participantes.put(f.getFileName().toString(), lista);
            }
        } catch (IOException e) {
            System.out.println("Erro ao ler monitor-*.txt: " + e.getMessage());
        }

        // ----------------------------------------------------------------
        // 3. Classificação das suspeitas
        // ----------------------------------------------------------------
        System.out.println();
        System.out.println("----- SUSPEITAS POR PARTICIPANTE × PARTIÇÃO -----");

        List<Suspeita> suspeitas = new ArrayList<>();

        for (Map.Entry<String, List<Evento>> parte : participantes.entrySet()) {
            for (Evento e : parte.getValue()) {
                if (!e.tipo.equals("SUSPEITA")) continue;

                Suspeita s = new Suspeita();
                s.participante = parte.getKey();
                s.k = e.k;
                s.instante = e.instante;
                s.silencioMs = extrairNumero(e.detalhes, "silencio_ms");

                // Último heartbeat de p<e.k> conhecido até ao instante da suspeita
                Hb ultimo = null;
                for (Hb h : hbs.get(e.k)) {
                    if (h.instante <= e.instante && (ultimo == null || h.instante >= ultimo.instante)) {
                        ultimo = h;
                    }
                }
                s.instanteUltimoHb = ultimo == null ? -1 : ultimo.instante;

                // Falsa suspeita: surge depois um heartbeat da MESMA encarnação
                boolean falsa = false;
                if (ultimo != null) {
                    for (Hb h : hbs.get(e.k)) {
                        if (h.inc == ultimo.inc && h.instante > e.instante) {
                            falsa = true;
                            break;
                        }
                    }
                }
                s.falsa = falsa;
                suspeitas.add(s);
            }
        }

        // Associar falhas reais aos instantes de falha anotados
        Map<Integer, List<Long>> falhas = new LinkedHashMap<>();

        if (ficheiroFalhas != null && Files.isRegularFile(ficheiroFalhas)) {
            for (String linha : lerLinhas(ficheiroFalhas)) {
                if (linha.isBlank()) continue;
                String[] f = linha.trim().split(";", -1);
                if (f.length != 2 || !f[0].startsWith("p")) continue;
                try {
                    falhas.computeIfAbsent(Integer.parseInt(f[0].substring(1)), x -> new ArrayList<>())
                          .add(Long.parseLong(f[1]));
                } catch (NumberFormatException ex) {
                    System.out.println("AVISO: linha inválida em falhas.txt: " + linha);
                }
            }
        }

        // Deteção: suspeita real mais próxima DEPOIS do instante de falha
        Map<Suspeita, Long> deteccoes = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Long>> grupo : falhas.entrySet()) {
            for (long instanteFalha : grupo.getValue()) {
                Suspeita melhor = null;
                for (Suspeita s : suspeitas) {
                    if (s.falsa || s.k != grupo.getKey() || s.instante < instanteFalha) continue;
                    if (melhor == null || s.instante < melhor.instante) melhor = s;
                }
                if (melhor != null) {
                    deteccoes.put(melhor, melhor.instante - instanteFalha);
                } else {
                    System.out.printf(loc,
                        "AVISO: falha p%d em %d ms sem suspeita real registada%n",
                        grupo.getKey(), instanteFalha);
                }
            }
        }

        int reais = 0;
        int falsas = 0;
        for (Suspeita s : suspeitas) {
            if (s.falsa) {
                falsas++;
                System.out.printf(loc,
                    "  %s: SUSPEITA p%d em %d (silêncio %d ms desde %d) — FALSA SUSPEITA (líder retomou a mesma encarnação)%n",
                    s.participante, s.k, s.instante, s.silencioMs, s.instanteUltimoHb);
            } else {
                reais++;
                Long det = deteccoes.get(s);
                String extra = det == null ? "" : String.format(loc, " — FALHA REAL, deteção em %d ms", det);
                System.out.printf(loc,
                    "  %s: SUSPEITA p%d em %d (silêncio %d ms desde %d)%s%n",
                    s.participante, s.k, s.instante, s.silencioMs, s.instanteUltimoHb, extra);
            }
        }
        if (suspeitas.isEmpty()) {
            System.out.println("  (sem suspeitas registadas)");
        }

        System.out.println();
        System.out.println("Suspeitas: " + reais + " reais, " + falsas + " falsas");

        // Tempos de deteção por partição
        if (!deteccoes.isEmpty()) {
            System.out.println();
            System.out.println("----- TEMPOS DE DETEÇÃO (falhas reais) -----");
            for (Map.Entry<Integer, List<Long>> grupo : falhas.entrySet()) {
                List<Long> tempos = new ArrayList<>();
                for (Map.Entry<Suspeita, Long> d : deteccoes.entrySet()) {
                    if (d.getKey().k == grupo.getKey()) tempos.add(d.getValue());
                }
                if (tempos.isEmpty()) continue;
                double media = tempos.stream().mapToLong(Long::longValue).average().orElse(0);
                long min = tempos.stream().min(Long::compare).get();
                long max = tempos.stream().max(Long::compare).get();
                System.out.printf(loc,
                    "  p%d: detecção min=%.1f s média=%.1f s máx=%.1f s (%d observações)%n",
                    grupo.getKey(), min / 1000.0, media / 1000.0, max / 1000.0, tempos.size());
            }
            System.out.println("  Janela esperada com Δ=5000 ms e FATOR_TIMEOUT=3: ]10 s, 16 s]");
        }

        // ----------------------------------------------------------------
        // 4. Latência dos lotes por registo aplicado
        // ----------------------------------------------------------------
        System.out.println();
        System.out.println("----- LATÊNCIA DOS LOTES (instante_recebido - instante_registo) -----");

        for (int k = 0; k < N; k++) {
            List<Long> latencias = new ArrayList<>();
            for (List<Evento> lista : participantes.values()) {
                for (Evento e : lista) {
                    if (!e.tipo.equals("APLICADO") || e.k != k) continue;
                    long instanteAplicacao = e.instante;
                    String registo = extrairValor(e.detalhes, "registo");
                    if (registo == null) continue;
                    try {
                        long instanteRegisto = LocalDateTime.parse(registo)
                                .atZone(ZoneId.systemDefault())
                                .toInstant().toEpochMilli();
                        latencias.add(instanteAplicacao - instanteRegisto);
                    } catch (Exception ex) {
                        // registo com formato de tempo inválido: ignorar
                    }
                }
            }
            if (latencias.isEmpty()) {
                System.out.printf(loc, "  p%d: (sem registos aplicados)%n", k);
                continue;
            }
            double media = latencias.stream().mapToLong(Long::longValue).average().orElse(0);
            long min = latencias.stream().min(Long::compare).get();
            long max = latencias.stream().max(Long::compare).get();
            System.out.printf(loc,
                "  p%d: latência média=%.0f ms máx=%d ms min=%d ms (%d registos)%n",
                k, media, max, min, latencias.size());
        }

        // ----------------------------------------------------------------
        // 5. Contagem de eventos notáveis
        // ----------------------------------------------------------------
        long hbPerdido = 0;
        long recuperacoes = 0;
        long reinicios = 0;
        for (List<Evento> lista : participantes.values()) {
            for (Evento e : lista) {
                if (e.tipo.equals("HB_PERDIDO")) hbPerdido++;
                else if (e.tipo.equals("RECUPERACAO")) recuperacoes++;
                else if (e.tipo.equals("REINICIO")) reinicios++;
            }
        }
        System.out.println();
        System.out.println("----- EVENTOS NOTÁVEIS (todos os participantes) -----");
        System.out.println("  HB_PERDIDO: " + hbPerdido);
        System.out.println("  RECUPERACAO: " + recuperacoes);
        System.out.println("  REINICIO: " + reinicios);

        // ----------------------------------------------------------------
        // 6. Custo em bytes: LOTE vs REG individual
        // ----------------------------------------------------------------
        System.out.println();
        System.out.println("----- CUSTO EM BYTES POR REGISTO -----");

        for (int k = 0; k < N; k++) {
            double somaProporcional = 0;
            long lotesComRegistos = 0;
            for (Hb e : hbs.get(k)) {
                if (e.n > 0 && e.bytes > 0) {
                    somaProporcional += e.bytes / (double) e.n;
                    lotesComRegistos++;
                }
            }
            Double mediaLote = lotesComRegistos == 0 ? null : somaProporcional / lotesComRegistos;

            // REG equivalente: REG;127.0.0.1;700<k>;<linha>
            Path lider = pasta.resolve("lider-p" + k + ".txt");
            Double mediaReg = null;
            if (Files.isRegularFile(lider)) {
                long soma = 0;
                long conta = 0;
                String prefixo = "REG;127.0.0.1;" + (Wire.PORTO_BASE + k) + ";";
                for (String linha : lerLinhas(lider)) {
                    if (linha.isBlank()) continue;
                    soma += prefixo.length() + linha.trim().length();
                    conta++;
                }
                if (conta > 0) mediaReg = (double) soma / conta;
            }

            String loteTxt = mediaLote == null ? "n/d" : String.format(loc, "%.1f", mediaLote);
            String regTxt = mediaReg == null ? "n/d" : String.format(loc, "%.1f", mediaReg);
            System.out.printf(loc,
                "  p%d: bytes/registo em LOTE=%s vs REG individual=%s%n",
                k, loteTxt, regTxt);
        }

        System.out.println();
        System.out.println("=====================================");
    }

    /** Lê todas as linhas de um ficheiro; devolve lista vazia se não existir. */
    static List<String> lerLinhas(Path caminho) {
        try {
            return Files.readAllLines(caminho, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Extrai o número associado a <chave>= dentro de uma sequência de detalhes. */
    static long extrairNumero(String detalhes, String chave) {
        String v = extrairValor(detalhes, chave);
        if (v == null) return -1;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Extrai o valor associado a <chave>= dentro de uma sequência de detalhes. */
    static String extrairValor(String detalhes, String chave) {
        for (String campo : detalhes.split(";")) {
            String c = campo.trim();
            if (c.startsWith(chave + "=")) {
                return c.substring(chave.length() + 1);
            }
        }
        return null;
    }
}
