
package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeartbeatCheck {

    static final Locale LOCALE = Locale.US;

    // -------------------------
    // Estruturas auxiliares
    // -------------------------

    static class HB {

        final int k;
        final long inc;
        final long hb;
        final long instante;
        final long ultimoSeq;
        final int n;
        final int bytes;

        HB(int k, String[] f) {

            this.k = k;
            inc = Long.parseLong(f[0]);
            hb = Long.parseLong(f[1]);
            instante = Long.parseLong(f[2]);
            ultimoSeq = Long.parseLong(f[3]);
            n = Integer.parseInt(f[4]);
            bytes = Integer.parseInt(f[5]);

            if (inc < 0 || hb < 0 || instante < 0
                    || ultimoSeq < 0 || n < 0
                    || bytes < 0) {

                throw new IllegalArgumentException(
                    "Valores negativos no heartbeat"
                );
            }
        }
    }

    static class Evento {

        final String participante;
        final String tipo;
        final String texto;
        final int k;
        final long instante;

        Long inc;
        Long hb;
        Long ultimoSeq;

        Integer n;

        Long de;
        Long ate;

        Evento(
                String participante,
                int k,
                long instante,
                String tipo,
                String texto) {

            this.participante = participante;
            this.k = k;
            this.instante = instante;
            this.tipo = tipo;
            this.texto = texto;
        }
    }

    static class Falha {

        final int k;
        final long instante;

        Falha(int k, long instante) {
            this.k = k;
            this.instante = instante;
        }
    }

    static class Suspeita {

        final Evento evento;
        final String classe;
        final Long silencio;

        Suspeita(
                Evento evento,
                String classe,
                Long silencio) {

            this.evento = evento;
            this.classe = classe;
            this.silencio = silencio;
        }
    }

    static class Estatistica {

        long quantidade = 0;
        double soma = 0;
        double maximo = 0;

        void adicionar(double valor) {

            quantidade++;
            soma += valor;

            maximo = Math.max(maximo, valor);
        }

        double media() {

            return quantidade == 0
                    ? 0
                    : soma / quantidade;
        }
    }

    static class PrecisaoLatencia {

        long semMilissegundos = 0;
        long comMilissegundos = 0;
    }

    // -------------------------
    // Expressões regulares
    // -------------------------

    static final Pattern PARTICAO = Pattern.compile(
        "(?:^|[;\\s])p(\\d+)(?=\\D|$)"
    );

    static final Pattern TIPO = Pattern.compile(
        "(?:^|[;\\s])"
        + "(SUSPEITA|RETOMA|REINICIO|HB_PERDIDO|"
        + "RECUPERACAO|RECEBIDO)"
        + "(?=[;\\s]|$)"
    );

    static final Pattern INTERVALO = Pattern.compile(
        "intervalo=(\\d+)\\.\\.(\\d+)"
    );

    // -------------------------
    // Utilitários
    // -------------------------

    static void aviso(String mensagem) {

        System.out.println(
            "AVISO: " + mensagem
        );
    }

    static Long campoLong(
            String texto,
            String nome) {

        Matcher m = Pattern.compile(
            "(?:^|[;\\s])"
            + Pattern.quote(nome)
            + "=(\\d+)(?=[;\\s]|$)"
        ).matcher(texto);

        return m.find()
                ? Long.parseLong(m.group(1))
                : null;
    }

    static Integer campoInt(
            String texto,
            String nome) {

        Long valor = campoLong(texto, nome);

        if (valor == null) {
            return null;
        }

        return Math.toIntExact(valor);
    }

    static String chave(
            int k,
            long inc,
            long hb) {

        return k + ";" + inc + ";" + hb;
    }

    static String descricao(Evento e) {

        return e.participante + " p" + e.k;
    }

    // -------------------------
    // Leitura dos heartbeats
    // -------------------------

    /**
     * Lê os heartbeats publicados por cada líder.
     *
     * Formato:
     * inc;hb;instante;ultimoSeq;n;bytes
     *
     * Preserva a ordem das linhas do ficheiro,
     * porque essa é a ordem de publicação registada.
     */
    static Map<Integer, List<HB>> lerHeartbeats(
            Path pasta,
            int N) throws IOException {

        Map<Integer, List<HB>> resultado =
                new TreeMap<>();

        for (int k = 0; k < N; k++) {

            List<HB> lista = new ArrayList<>();

            resultado.put(k, lista);

            Path ficheiro = pasta.resolve(
                "hb-p" + k + ".txt"
            );

            if (!Files.isRegularFile(ficheiro)) {

                aviso(
                    "Ficheiro não encontrado: "
                    + ficheiro.getFileName()
                );

                continue;
            }

            int numero = 0;

            for (String linha : Files.readAllLines(
                    ficheiro,
                    StandardCharsets.UTF_8)) {

                numero++;

                if (linha.isBlank()) {
                    continue;
                }

                try {

                    String[] f = linha.trim().split(
                        ";", -1
                    );

                    if (f.length != 6) {

                        throw new IllegalArgumentException(
                            "Esperados 6 campos"
                        );
                    }

                    lista.add(new HB(k, f));

                } catch (IllegalArgumentException e) {

                    aviso(
                        ficheiro.getFileName()
                        + ":" + numero
                        + ": " + e.getMessage()
                    );
                }
            }

            // Não ordenar por hb:
            // preservar a ordem das publicações
            // presente no ficheiro.
        }

        return resultado;
    }

    // -------------------------
    // Leitura dos registos
    // -------------------------

    /**
     * Lê os registos persistidos pelos líderes.
     *
     * Formato:
     * seq;sensor;temperatura;instante;L;V
     */
    static Map<Integer, Map<Long, SensorRecord>>
            lerRegistos(
                Path pasta,
                int N) throws IOException {

        Map<Integer, Map<Long, SensorRecord>> resultado =
                new TreeMap<>();

        for (int k = 0; k < N; k++) {

            Map<Long, SensorRecord> registos =
                    new HashMap<>();

            resultado.put(k, registos);

            Path ficheiro = pasta.resolve(
                "lider-p" + k + ".txt"
            );

            if (!Files.isRegularFile(ficheiro)) {

                aviso(
                    "Ficheiro não encontrado: "
                    + ficheiro.getFileName()
                );

                continue;
            }

            int numero = 0;

            for (String linha : Files.readAllLines(
                    ficheiro,
                    StandardCharsets.UTF_8)) {

                numero++;

                if (linha.isBlank()) {
                    continue;
                }

                try {

                    SensorRecord r =
                            SensorRecord.fromLine(
                                linha, N
                            );

                    registos.put(
                        r.getSeq(), r
                    );

                } catch (IllegalArgumentException e) {

                    aviso(
                        ficheiro.getFileName()
                        + ":" + numero
                        + ": registo inválido: "
                        + e.getMessage()
                    );
                }
            }
        }

        return resultado;
    }

    // -------------------------
    // Leitura dos monitores
    // -------------------------

    /**
     * Lê os eventos dos ficheiros monitor-*.txt.
     *
     * Formato:
     * instante;evento;p<k>;...
     */
    static List<Evento> lerMonitores(
            Path pasta,
            int N) throws IOException {

        List<Evento> todos = new ArrayList<>();

        try (DirectoryStream<Path> ficheiros =
                Files.newDirectoryStream(
                    pasta, "monitor-*.txt"
                )) {

            for (Path ficheiro : ficheiros) {

                String nome =
                        ficheiro.getFileName().toString();

                String participante = nome.substring(
                    "monitor-".length(),
                    nome.length() - ".txt".length()
                );

                List<Evento> eventos =
                        new ArrayList<>();

                int numero = 0;

                for (String linha : Files.readAllLines(
                        ficheiro,
                        StandardCharsets.UTF_8)) {

                    numero++;

                    if (linha.isBlank()) {
                        continue;
                    }

                    try {

                        int separador =
                                linha.indexOf(';');

                        if (separador <= 0) {

                            throw new IllegalArgumentException(
                                "Instante ou evento em falta"
                            );
                        }

                        long instante = Long.parseLong(
                            linha.substring(
                                0, separador
                            ).trim()
                        );

                        String texto = linha.substring(
                            separador + 1
                        ).trim();

                        Matcher tipo =
                                TIPO.matcher(texto);

                        Matcher particao =
                                PARTICAO.matcher(texto);

                        if (!tipo.find()
                                || !particao.find()) {
                            continue;
                        }

                        int k = Integer.parseInt(
                            particao.group(1)
                        );

                        if (k < 0 || k >= N) {

                            aviso(
                                nome + ":" + numero
                                + ": partição inválida"
                            );

                            continue;
                        }

                        Evento e = new Evento(
                            participante,
                            k,
                            instante,
                            tipo.group(1),
                            texto
                        );

                        e.inc = campoLong(
                            texto, "inc"
                        );

                        e.hb = campoLong(
                            texto, "hb"
                        );

                        e.ultimoSeq = campoLong(
                            texto, "ultimoSeq"
                        );

                        e.n = campoInt(
                            texto, "n"
                        );

                        if ("RECEBIDO".equals(e.tipo)) {

                            Matcher intervalo =
                                    INTERVALO.matcher(texto);

                            if (intervalo.find()) {

                                e.de = Long.parseLong(
                                    intervalo.group(1)
                                );

                                e.ate = Long.parseLong(
                                    intervalo.group(2)
                                );
                            }
                        }

                        eventos.add(e);

                    } catch (IllegalArgumentException e) {

                        aviso(
                            nome + ":" + numero
                            + ": " + e.getMessage()
                        );
                    }
                }

                eventos.sort(
                    Comparator.comparingLong(
                        e -> e.instante
                    )
                );

                // Inferir inc quando o evento não
                // indicar explicitamente a incarnação.
                Map<Integer, Long> ultimaInc =
                        new HashMap<>();

                for (Evento e : eventos) {

                    if (e.inc == null) {

                        e.inc = ultimaInc.get(e.k);

                    } else {

                        ultimaInc.put(e.k, e.inc);
                    }
                }

                todos.addAll(eventos);
            }
        }

        return todos;
    }

    // -------------------------
    // Leitura das falhas
    // -------------------------

    /**
     * Formato:
     * p<k>;instante
     */
    static List<Falha> lerFalhas(
            Path ficheiro,
            int N) throws IOException {

        List<Falha> falhas = new ArrayList<>();

        int numero = 0;

        for (String linha : Files.readAllLines(
                ficheiro,
                StandardCharsets.UTF_8)) {

            numero++;

            if (linha.isBlank()) {
                continue;
            }

            String[] f = linha.trim().split(
                ";", -1
            );

            try {

                if (f.length != 2
                        || !f[0].matches("p\\d+")) {

                    throw new IllegalArgumentException(
                        "Formato esperado: p<k>;instante"
                    );
                }

                int k = Integer.parseInt(
                    f[0].substring(1)
                );

                long instante = Long.parseLong(
                    f[1]
                );

                if (k < 0 || k >= N
                        || instante < 0) {

                    throw new IllegalArgumentException(
                        "Valores inválidos"
                    );
                }

                falhas.add(
                    new Falha(k, instante)
                );

            } catch (IllegalArgumentException e) {

                aviso(
                    ficheiro.getFileName()
                    + ":" + numero
                    + ": " + e.getMessage()
                );
            }
        }

        falhas.sort(
            Comparator.comparingLong(
                f -> f.instante
            )
        );

        return falhas;
    }

    // -------------------------
    // Relatório dos lotes
    // -------------------------

    /**
     * Estatísticas por partição e incarnação.
     *
     * CORREÇÃO:
     *
     * O intervalo é calculado entre duas
     * linhas publicadas consecutivas da
     * mesma incarnação, mesmo que os valores
     * de hb não sejam consecutivos.
     *
     * Nunca calcula intervalos entre
     * incarnações diferentes.
     */
    static void relatorioLotes(
            Map<Integer, List<HB>> heartbeats) {

        System.out.println();
        System.out.println(
            "========== LOTES E HEARTBEATS =========="
        );

        for (Map.Entry<Integer, List<HB>> entrada
                : heartbeats.entrySet()) {

            int k = entrada.getKey();

            // Agrupar por incarnação, preservando
            // a ordem original das publicações.
            Map<Long, List<HB>> porInc =
                    new TreeMap<>();

            for (HB h : entrada.getValue()) {

                porInc.computeIfAbsent(
                    h.inc,
                    x -> new ArrayList<>()
                ).add(h);
            }

            System.out.println();
            System.out.println(
                "Partição p" + k
            );

            if (porInc.isEmpty()) {

                System.out.println(
                    "  Sem heartbeats publicados."
                );

                continue;
            }

            for (Map.Entry<Long, List<HB>> grupo
                    : porInc.entrySet()) {

                List<HB> lista =
                        grupo.getValue();

                long vazios = 0;
                long intervalosNegativos = 0;

                Estatistica registos =
                        new Estatistica();

                Estatistica intervalos =
                        new Estatistica();

                HB anterior = null;

                for (HB h : lista) {

                    if (h.n == 0) {
                        vazios++;
                    }

                    registos.adicionar(h.n);

                    // CORREÇÃO:
                    // Comparar cada publicação com
                    // a publicação anterior da mesma inc.
                    //
                    // Não exigir hb consecutivos.
                    if (anterior != null) {

                        long intervalo =
                                h.instante
                                - anterior.instante;

                        if (intervalo >= 0) {

                            intervalos.adicionar(
                                intervalo
                            );

                        } else {

                            intervalosNegativos++;

                            aviso(
                                "Intervalo negativo em p"
                                + k
                                + " inc=" + h.inc
                                + " entre hb="
                                + anterior.hb
                                + " e hb=" + h.hb
                            );
                        }
                    }

                    anterior = h;
                }

                double percentagem =
                        100.0 * vazios / lista.size();

                System.out.println(
                    "  inc=" + grupo.getKey()
                );

                System.out.println(
                    "    Lotes: " + lista.size()
                );

                System.out.printf(
                    LOCALE,
                    "    Lotes vazios: %d (%.2f%%)%n",
                    vazios,
                    percentagem
                );

                System.out.printf(
                    LOCALE,
                    "    Registos/lote: média=%.2f "
                    + "máximo=%.0f%n",
                    registos.media(),
                    registos.maximo
                );

                if (intervalos.quantidade == 0) {

                    System.out.println(
                        "    Intervalos: não aplicável"
                    );

                } else {

                    System.out.printf(
                        LOCALE,
                        "    Intervalo HB: "
                        + "média=%.2f ms "
                        + "máximo=%.0f ms "
                        + "(%d intervalos)%n",
                        intervalos.media(),
                        intervalos.maximo,
                        intervalos.quantidade
                    );
                }

                if (intervalosNegativos > 0) {

                    System.out.println(
                        "    Intervalos negativos "
                        + "rejeitados: "
                        + intervalosNegativos
                    );
                }
            }
        }
    }

    // -------------------------
    // Análise das suspeitas
    // -------------------------

    /**
     * Silêncio entre o último heartbeat
     * publicado da mesma incarnação e
     * o instante da suspeita.
     */
    static Long silencioPublicado(
            Evento suspeita,
            Map<Integer, List<HB>> heartbeats) {

        if (suspeita.inc == null) {
            return null;
        }

        Long ultimo = null;

        for (HB h : heartbeats.get(suspeita.k)) {

            if (h.inc == suspeita.inc
                    && h.instante <= suspeita.instante) {

                if (ultimo == null
                        || h.instante > ultimo) {

                    ultimo = h.instante;
                }
            }
        }

        return ultimo == null
                ? null
                : suspeita.instante - ultimo;
    }

    static boolean houvePublicacaoPosterior(
            Evento suspeita,
            Map<Integer, List<HB>> heartbeats) {

        if (suspeita.inc == null) {
            return false;
        }

        for (HB h : heartbeats.get(suspeita.k)) {

            if (h.inc == suspeita.inc
                    && h.instante > suspeita.instante) {

                return true;
            }
        }

        return false;
    }

    /**
     * Classifica as suspeitas.
     */
    static List<Suspeita> relatorioSuspeitas(
            List<Evento> eventos,
            Map<Integer, List<HB>> heartbeats) {

        System.out.println();
        System.out.println(
            "========== SUSPEITAS =========="
        );

        List<Suspeita> resultado =
                new ArrayList<>();

        List<Evento> suspeitas =
                new ArrayList<>();

        for (Evento e : eventos) {

            if ("SUSPEITA".equals(e.tipo)) {

                suspeitas.add(e);
            }
        }

        suspeitas.sort(
            Comparator
                .comparing(
                    (Evento e) -> e.participante
                )
                .thenComparingInt(
                    e -> e.k
                )
                .thenComparingLong(
                    e -> e.instante
                )
        );

        long reais = 0;
        long falsas = 0;
        long indeterminadas = 0;

        for (Evento s : suspeitas) {

            boolean retoma = false;

            for (Evento e : eventos) {

                if (!"RETOMA".equals(e.tipo)
                        || !e.participante.equals(
                            s.participante)
                        || e.k != s.k
                        || e.instante <= s.instante) {

                    continue;
                }

                if (s.inc != null
                        && s.inc.equals(e.inc)) {

                    retoma = true;
                    break;
                }
            }

            String classe;

            if (retoma) {

                classe = "FALSA_SUSPEITA";
                falsas++;

            } else if (s.inc != null
                    && !houvePublicacaoPosterior(
                        s, heartbeats)) {

                classe = "FALHA_REAL";
                reais++;

            } else {

                classe = "INDETERMINADA";
                indeterminadas++;
            }

            Long silencio = silencioPublicado(
                s, heartbeats
            );

            resultado.add(
                new Suspeita(
                    s, classe, silencio
                )
            );

            System.out.println(
                s.participante
                + " p" + s.k
                + " instante=" + s.instante
                + " inc="
                + (s.inc == null ? "?" : s.inc)
                + " → " + classe
                + " silêncio="
                + (silencio == null
                    ? "não disponível"
                    : silencio + " ms")
            );
        }

        if (suspeitas.isEmpty()) {

            System.out.println(
                "Sem suspeitas registadas."
            );
        }

        System.out.println(
            "Total: reais=" + reais
            + " falsas=" + falsas
            + " indeterminadas=" + indeterminadas
        );

        return resultado;
    }

    // -------------------------
    // Tempos de deteção
    // -------------------------

    static void relatorioDetecao(
            List<Falha> falhas,
            List<Suspeita> suspeitas) {

        System.out.println();
        System.out.println(
            "========== TEMPOS DE DETEÇÃO =========="
        );

        if (falhas.isEmpty()) {

            System.out.println(
                "Sem falhas anotadas."
            );
            return;
        }

        for (Falha f : falhas) {

            long limite = Long.MAX_VALUE;

            for (Falha seguinte : falhas) {

                if (seguinte.k == f.k
                        && seguinte.instante > f.instante) {

                    limite = Math.min(
                        limite,
                        seguinte.instante
                    );
                }
            }

            Map<String, Suspeita> primeiras =
                    new TreeMap<>();

            for (Suspeita s : suspeitas) {

                Evento e = s.evento;

                if (!"FALHA_REAL".equals(s.classe)
                        || e.k != f.k
                        || e.instante < f.instante
                        || e.instante >= limite) {

                    continue;
                }

                Suspeita anterior =
                        primeiras.get(
                            e.participante
                        );

                if (anterior == null
                        || e.instante
                        < anterior.evento.instante) {

                    primeiras.put(
                        e.participante, s
                    );
                }
            }

            System.out.println(
                "Falha p" + f.k
                + " instante=" + f.instante
            );

            if (primeiras.isEmpty()) {

                System.out.println(
                    "  Sem deteções reais identificáveis."
                );
            }

            for (Map.Entry<String, Suspeita> entrada
                    : primeiras.entrySet()) {

                long deteccao =
                        entrada.getValue()
                        .evento.instante
                        - f.instante;

                System.out.println(
                    "  " + entrada.getKey()
                    + ": deteção="
                    + deteccao + " ms"
                );
            }
        }
    }

    // -------------------------
    // Precisão temporal
    // -------------------------

    /**
     * Converte o instante textual do SensorRecord
     * para milissegundos desde a época Unix.
     *
     * Aceita instantes com e sem fração de segundo:
     *
     * 2026-11-02T12:30:15
     * 2026-11-02T12:30:15.347
     *
     * O fuso horário utilizado é o fuso local
     * da JVM, tal como no código anterior.
     */
    static long instanteRegisto(SensorRecord r) {

        return LocalDateTime.parse(
            r.getTimestamp()
        )
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli();
    }

    /**
     * Indica se o instante foi gravado
     * com uma parte fracionária de segundo.
     *
     * Exemplo:
     * 12:30:15     -> false
     * 12:30:15.347 -> true
     */
    static boolean temFracaoSegundo(
            SensorRecord r) {

        return r.getTimestamp().contains(".");
    }

    // -------------------------
    // Latências dos lotes
    // -------------------------

    /**
     * Latência:
     *
     * instante RECEBIDO - instante do registo
     *
     * CORREÇÃO:
     * O relatório distingue instantes gravados
     * com precisão de segundos dos instantes
     * que conservam milissegundos.
     */
    static void relatorioLatencias(
            int N,
            List<Evento> eventos,
            Map<Integer, Map<Long, SensorRecord>> registos,
            Map<Integer, List<HB>> heartbeats) {

        System.out.println();
        System.out.println(
            "========== LATÊNCIA DE LOTE =========="
        );

        Map<String, HB> indiceHB =
                new HashMap<>();

        for (List<HB> lista : heartbeats.values()) {

            for (HB h : lista) {

                indiceHB.put(
                    chave(h.k, h.inc, h.hb),
                    h
                );
            }
        }

        for (int k = 0; k < N; k++) {

            Estatistica latencias =
                    new Estatistica();

            PrecisaoLatencia precisao =
                    new PrecisaoLatencia();

            Set<String> contabilizados =
                    new HashSet<>();

            long indisponiveis = 0;

            for (Evento e : eventos) {

                if (e.k != k
                        || !"RECEBIDO".equals(e.tipo)
                        || e.inc == null
                        || e.hb == null) {

                    continue;
                }

                String id = chave(
                    k, e.inc, e.hb
                );

                HB h = indiceHB.get(id);

                if (h == null) {

                    indisponiveis++;
                    continue;
                }

                // Heartbeats vazios não contêm
                // registos para medir latência.
                if (h.n == 0) {
                    continue;
                }

                // Intervalo dos registos do lote
                long de = h.ultimoSeq - h.n + 1;
                long ate = h.ultimoSeq;

                if (e.de != null
                        && e.ate != null
                        && (!e.de.equals(de)
                            || !e.ate.equals(ate))) {

                    aviso(
                        "Intervalo inconsistente em "
                        + e.participante
                        + " p" + k
                        + " hb=" + e.hb
                    );

                    continue;
                }

                for (long seq = de; seq <= ate; seq++) {

                    String observacao =
                            e.participante
                            + ";" + id
                            + ";" + seq;

                    if (!contabilizados.add(
                            observacao)) {

                        continue;
                    }

                    SensorRecord r =
                            registos.get(k).get(seq);

                    if (r == null) {

                        indisponiveis++;
                        continue;
                    }

                    try {

                        long inicio =
                                instanteRegisto(r);

                        long latencia =
                                e.instante - inicio;

                        if (latencia < 0) {

                            indisponiveis++;
                            continue;
                        }

                        latencias.adicionar(
                            latencia
                        );

                        // Identificar a precisão
                        // disponível no registo.
                        if (temFracaoSegundo(r)) {

                            precisao.comMilissegundos++;

                        } else {

                            precisao.semMilissegundos++;
                        }

                    } catch (DateTimeParseException ex) {

                        indisponiveis++;
                    }
                }
            }

            if (latencias.quantidade == 0) {

                System.out.println(
                    "p" + k
                    + ": latência não disponível"
                    + " (observações indisponíveis="
                    + indisponiveis + ")"
                );

            } else {

                System.out.printf(
                    LOCALE,
                    "p%d: média=%.2f ms "
                    + "máximo=%.0f ms "
                    + "amostras=%d "
                    + "indisponíveis=%d%n",
                    k,
                    latencias.media(),
                    latencias.maximo,
                    latencias.quantidade,
                    indisponiveis
                );
            }

            System.out.println(
                "  Instantes com fração de segundo: "
                + precisao.comMilissegundos
            );

            System.out.println(
                "  Instantes sem fração de segundo: "
                + precisao.semMilissegundos
            );

            // Aviso explícito da limitação
            // dos registos antigos.
            if (precisao.semMilissegundos > 0) {

                System.out.println(
                    "  AVISO: existem registos cujo "
                    + "instante foi guardado apenas "
                    + "com precisão de segundos."
                );

                System.out.println(
                    "  Cada latência desses registos "
                    + "pode ter um erro de truncagem "
                    + "de até quase 1000 ms."
                );

                System.out.println(
                    "  Os milissegundos perdidos "
                    + "não podem ser reconstruídos "
                    + "pelo HeartbeatCheck."
                );
            }
        }
    }

    // -------------------------
    // Contagem dos eventos
    // -------------------------

    static void relatorioEventos(
            List<Evento> eventos) {

        System.out.println();
        System.out.println(
            "========== EVENTOS =========="
        );

        Map<String, long[]> totais =
                new TreeMap<>();

        for (Evento e : eventos) {

            if (!e.tipo.equals("HB_PERDIDO")
                    && !e.tipo.equals("RECUPERACAO")) {

                continue;
            }

            String chave = descricao(e);

            long[] c = totais.computeIfAbsent(
                chave,
                x -> new long[2]
            );

            if (e.tipo.equals("HB_PERDIDO")) {

                c[0]++;

            } else {

                c[1]++;
            }
        }

        if (totais.isEmpty()) {

            System.out.println(
                "Sem eventos HB_PERDIDO "
                + "ou RECUPERACAO."
            );
        }

        long perdidos = 0;
        long recuperacoes = 0;

        for (Map.Entry<String, long[]> entrada
                : totais.entrySet()) {

            long[] c = entrada.getValue();

            perdidos += c[0];
            recuperacoes += c[1];

            System.out.println(
                entrada.getKey()
                + ": HB_PERDIDO=" + c[0]
                + " RECUPERACAO=" + c[1]
            );
        }

        System.out.println(
            "Total HB_PERDIDO=" + perdidos
            + " RECUPERACAO=" + recuperacoes
        );
    }

    // -------------------------
    // Comparação dos bytes
    // -------------------------

    /**
     * Calcula o tamanho médio por registo.
     *
     * LOTE:
     * bytes dos lotes não vazios / registos
     *
     * REG:
     * tamanho de uma mensagem equivalente
     * da COMINDIR03.
     */
    static void relatorioBytes(
            int N,
            Map<Integer, List<HB>> heartbeats,
            Map<Integer, Map<Long, SensorRecord>> registos) {

        System.out.println();
        System.out.println(
            "========== TAMANHO DAS MENSAGENS =========="
        );

        long bytesLote = 0;
        long quantidadeLote = 0;

        long bytesREG = 0;
        long quantidadeREG = 0;

        for (int k = 0; k < N; k++) {

            String host = "127.0.0.1";
            int porto = Wire.PORTO_BASE + k;

            for (HB h : heartbeats.get(k)) {

                if (h.n <= 0) {
                    continue;
                }

                bytesLote += h.bytes;
                quantidadeLote += h.n;

                long de = h.ultimoSeq - h.n + 1;

                for (long seq = de;
                        seq <= h.ultimoSeq; seq++) {

                    SensorRecord r =
                            registos.get(k).get(seq);

                    if (r == null) {
                        continue;
                    }

                    String mensagemREG =
                            "REG;" + host
                            + ";" + porto
                            + ";" + r.toLine();

                    bytesREG += mensagemREG.getBytes(
                        StandardCharsets.UTF_8
                    ).length;

                    quantidadeREG++;
                }
            }
        }

        if (quantidadeLote == 0) {

            System.out.println(
                "LOTE: sem registos transportados."
            );

        } else {

            System.out.printf(
                LOCALE,
                "LOTE: %.2f bytes/registo "
                + "(%d registos, %d bytes)%n",
                (double) bytesLote / quantidadeLote,
                quantidadeLote,
                bytesLote
            );
        }

        if (quantidadeREG == 0) {

            System.out.println(
                "REG equivalente: não disponível."
            );

        } else {

            System.out.printf(
                LOCALE,
                "REG equivalente: %.2f bytes/registo "
                + "(%d registos)%n",
                (double) bytesREG / quantidadeREG,
                quantidadeREG
            );
        }

        System.out.println(
            "Nota: comparação REG assume "
            + "host=127.0.0.1 e porto=7000+k."
        );

        System.out.println(
            "LOTE exclui heartbeats vazios "
            + "da média por registo."
        );
    }

    // -------------------------
    // Programa principal
    // -------------------------

    /**
     * Execução:
     * HeartbeatCheck <N> <pasta> [falhas.txt]
     */
    public static void main(String[] args) {

        if (args.length < 2 || args.length > 3) {

            System.out.println(
                "Uso: HeartbeatCheck "
                + "<N> <pasta> [falhas.txt]"
            );
            return;
        }

        int N;

        try {

            N = Integer.parseInt(args[0]);

            if (N <= 0) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {

            System.out.println(
                "N tem de ser um inteiro positivo."
            );
            return;
        }

        Path pasta = Paths.get(args[1]);

        if (!Files.isDirectory(pasta)) {

            System.out.println(
                "Pasta inválida: " + pasta
            );
            return;
        }

        try {

            System.out.println(
                "========== HEARTBEAT CHECK =========="
            );

            System.out.println(
                "Partições: " + N
            );

            System.out.println(
                "Pasta: " + pasta.toAbsolutePath()
            );

            Map<Integer, List<HB>> heartbeats =
                    lerHeartbeats(pasta, N);

            Map<Integer, Map<Long, SensorRecord>> registos =
                    lerRegistos(pasta, N);

            List<Evento> eventos =
                    lerMonitores(pasta, N);

            // Estatísticas dos heartbeats
            relatorioLotes(heartbeats);

            // Análise das suspeitas
            List<Suspeita> suspeitas =
                    relatorioSuspeitas(
                        eventos, heartbeats
                    );

            // Tempos de deteção, se existir
            // ficheiro de falhas provocado
            if (args.length == 3) {

                Path falhas = Paths.get(
                    args[2]
                );

                if (!falhas.isAbsolute()) {

                    falhas = pasta.resolve(
                        falhas
                    );
                }

                if (Files.isRegularFile(falhas)) {

                    List<Falha> anotadas =
                            lerFalhas(falhas, N);

                    relatorioDetecao(
                        anotadas, suspeitas
                    );

                } else {

                    aviso(
                        "Ficheiro de falhas "
                        + "não encontrado: " + falhas
                    );
                }
            }

            // Latência e precisão dos instantes
            relatorioLatencias(
                N,
                eventos,
                registos,
                heartbeats
            );

            // HB_PERDIDO e RECUPERACAO
            relatorioEventos(
                eventos
            );

            // Comparação entre LOTE e REG
            relatorioBytes(
                N,
                heartbeats,
                registos
            );

            System.out.println();
            System.out.println(
                "========== FIM DO RELATÓRIO =========="
            );

        } catch (IOException e) {

            System.out.println(
                "Erro ao analisar ficheiros: "
                + e.getMessage()
            );
        }
    }
}
