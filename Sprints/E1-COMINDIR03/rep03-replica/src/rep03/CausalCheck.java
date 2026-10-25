package rep03;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CausalCheck {
    private static final Pattern FICHEIRO_LIDER = Pattern.compile("^lider-p(\\d+)\\.txt$");

    private static final class Evento {
        final int k;
        final SensorRecord registo;
        final Path ficheiro;
        final int linha;
        final boolean vetorValido;

        Evento(int k, SensorRecord registo, Path ficheiro, int linha, boolean vetorValido) {
            this.k = k;
            this.registo = registo;
            this.ficheiro = ficheiro;
            this.linha = linha;
            this.vetorValido = vetorValido;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: CausalCheck <N> <pasta>");
            return;
        }
        int n = Integer.parseInt(args[0]);
        if (n <= 0) throw new IllegalArgumentException("N tem de ser > 0");
        Path pasta = Paths.get(args[1]);
        if (!Files.isDirectory(pasta)) throw new IllegalArgumentException("A pasta não existe: " + pasta);

        List<Path> ficheiros = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pasta)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && FICHEIRO_LIDER.matcher(path.getFileName().toString()).matches()) ficheiros.add(path);
            }
        }
        ficheiros.sort(Comparator.comparing(path -> path.getFileName().toString()));
        Map<Integer, Path> porParticao = new TreeMap<>();
        for (Path path : ficheiros) {
            Matcher m = FICHEIRO_LIDER.matcher(path.getFileName().toString());
            m.matches();
            int k = Integer.parseInt(m.group(1));
            if (porParticao.put(k, path) != null) System.out.println("ERRO: mais do que um ficheiro para p" + k);
        }

        List<Evento> eventos = new ArrayList<>();
        long linhasLidas = 0;
        long errosInvariante = 0;
        long errosSequencia = 0;
        long linhasInvalidas = 0;
        for (int k = 0; k < n; k++) {
            Path ficheiro = porParticao.get(k);
            if (ficheiro == null) {
                System.out.println("AVISO: não existe lider-p" + k + ".txt");
                continue;
            }
            long seqEsperada = 1;
            int numeroLinha = 0;
            for (String texto : Files.readAllLines(ficheiro, StandardCharsets.UTF_8)) {
                numeroLinha++;
                if (texto.isBlank()) continue;
                linhasLidas++;
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(texto);
                } catch (IllegalArgumentException e) {
                    linhasInvalidas++;
                    System.out.println("LINHA INVÁLIDA: " + ficheiro.getFileName() + ":" + numeroLinha + " — " + e.getMessage());
                    continue;
                }
                if (r.getSeq() != seqEsperada) {
                    errosSequencia++;
                    System.out.println("SEQUÊNCIA INVÁLIDA: " + ficheiro.getFileName() + ":" + numeroLinha
                            + " seq=" + r.getSeq() + ", esperado=" + seqEsperada);
                }
                seqEsperada = r.getSeq() + 1;
                int dimensao = r.getVector().toString().split(",", -1).length;
                boolean vetorValido = dimensao == n;
                if (!vetorValido) {
                    System.out.println("DIMENSÃO INVÁLIDA: " + ficheiro.getFileName() + ":" + numeroLinha
                            + " V tem dimensão " + dimensao + ", esperado=" + n);
                } else if (r.getVector().get(k) != r.getSeq()) {
                    errosInvariante++;
                    System.out.println("INVARIANTE VIOLADA: p" + k + " seq=" + r.getSeq()
                            + " mas V[" + k + "]=" + r.getVector().get(k));
                }
                eventos.add(new Evento(k, r, ficheiro, numeroLinha, vetorValido));
            }
        }

        long aAntesB = 0;
        long bAntesA = 0;
        long concorrentes = 0;
        long concorrentesLDistinto = 0;
        long vetoresIguais = 0;
        long violacoesLamport = 0;
        for (int i = 0; i < eventos.size(); i++) {
            Evento a = eventos.get(i);
            if (!a.vetorValido) continue;
            for (int j = i + 1; j < eventos.size(); j++) {
                Evento b = eventos.get(j);
                if (a.k == b.k || !b.vetorValido) continue;
                VectorClock.Order ordem = a.registo.getVector().compare(b.registo.getVector());
                if (ordem == VectorClock.Order.BEFORE) {
                    aAntesB++;
                    if (a.registo.getLamport() >= b.registo.getLamport()) {
                        violacoesLamport++;
                        imprimirViolacao(a, b);
                    }
                } else if (ordem == VectorClock.Order.AFTER) {
                    bAntesA++;
                    if (b.registo.getLamport() >= a.registo.getLamport()) {
                        violacoesLamport++;
                        imprimirViolacao(b, a);
                    }
                } else if (ordem == VectorClock.Order.CONCURRENT) {
                    concorrentes++;
                    if (a.registo.getLamport() != b.registo.getLamport()) concorrentesLDistinto++;
                } else {
                    vetoresIguais++;
                    System.out.println("VETORES IGUAIS EM EVENTOS DISTINTOS (não classificáveis como causalidade nem concorrência): "
                            + descrever(a) + " | " + descrever(b));
                }
            }
        }

        List<Evento> ordenados = new ArrayList<>(eventos);
        ordenados.sort(Comparator.comparingLong((Evento e) -> e.registo.getLamport())
                .thenComparingInt(e -> e.k).thenComparingLong(e -> e.registo.getSeq()));
        List<String> vista = new ArrayList<>();
        for (Evento e : ordenados) vista.add("p" + e.k + ";" + e.registo.toLine());
        Path vistaPath = pasta.resolve("vista-lamport.txt");
        Files.write(vistaPath, vista, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        long paresCausais = aAntesB + bAntesA;
        long paresDiferentes = paresCausais + concorrentes + vetoresIguais;
        double proporcao = concorrentes == 0 ? 0.0 : 100.0 * concorrentesLDistinto / concorrentes;
        System.out.println("\n=== Relatório CausalCheck (N=" + n + ") ===");
        System.out.println("Registos lidos: " + linhasLidas + " (eventos analisáveis: " + eventos.size() + ")");
        System.out.println("Pares de partições diferentes analisados: " + paresDiferentes);
        System.out.println("a → b: " + aAntesB);
        System.out.println("b → a: " + bAntesA);
        System.out.println("Total causal: " + paresCausais);
        System.out.println("Concorrentes: " + concorrentes);
        System.out.println("Vetores iguais/anómalos: " + vetoresIguais);
        System.out.println("Pares concorrentes com L distinto: " + concorrentesLDistinto + " ("
                + String.format(Locale.ROOT, "%.2f", proporcao) + "%)");
        System.out.println("Violações da condição de Lamport: " + violacoesLamport);
        System.out.println("Violações V[k] = seq: " + errosInvariante);
        System.out.println("Quebras/repetições de sequência: " + errosSequencia);
        System.out.println("Linhas inválidas: " + linhasInvalidas);
        System.out.println("Vista ordenada por (L,k): " + vistaPath.toAbsolutePath());
    }

    private static void imprimirViolacao(Evento a, Evento b) {
        System.out.println("VIOLAÇÃO DA CONDIÇÃO DE LAMPORT: " + descrever(a) + " → " + descrever(b)
                + " mas L(a)=" + a.registo.getLamport() + " não é < L(b)=" + b.registo.getLamport());
    }

    private static String descrever(Evento e) {
        return "p" + e.k + "#" + e.registo.getSeq() + " (L=" + e.registo.getLamport()
                + ", V=" + e.registo.getVector() + ")";
    }
}
