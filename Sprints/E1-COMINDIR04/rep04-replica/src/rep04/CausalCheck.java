package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CausalCheck {

    // Registo associado à partição de origem
    static class Evento {

        final int k;
        final SensorRecord r;

        Evento(int k, SensorRecord r) {
            this.k = k;
            this.r = r;
        }
    }

    // Contadores globais da verificação
    static long violacoesV = 0;
    static long violacoesLamport = 0;
    static long registosInvalidos = 0;
    static long anomaliasIguais = 0;

    /**
     * Lê os registos de uma partição.
     *
     * Verifica se V[k] == seq em todas as linhas.
     */
    static List<Evento> lerParticao(
            Path caminho,
            int k,
            int N) throws IOException {

        List<Evento> eventos = new ArrayList<>();

        int numeroLinha = 0;

        for (String linha : Files.readAllLines(
                caminho, StandardCharsets.UTF_8)) {

            numeroLinha++;

            if (linha.isBlank()) {
                continue;
            }

            try {

                SensorRecord r = SensorRecord.fromLine(
                    linha, N
                );

                // Verificar a componente da partição
                long componente = r.getVector().get(k);

                if (componente != r.getSeq()) {

                    System.out.println(
                        "VIOLAÇÃO V[k] == seq: p" + k
                        + " linha " + numeroLinha
                        + " V[" + k + "]=" + componente
                        + " seq=" + r.getSeq()
                    );

                    violacoesV++;
                }

                eventos.add(new Evento(k, r));

            } catch (IllegalArgumentException e) {

                System.out.println(
                    "REGISTO INVÁLIDO: p" + k
                    + " linha " + numeroLinha
                    + " (" + e.getMessage() + ")"
                );

                registosInvalidos++;
            }
        }

        return eventos;
    }

    /**
     * Execução:
     * CausalCheck <N> <pasta>
     */
    public static void main(String[] args) {

        if (args.length != 2) {

            System.out.println(
                "Uso: CausalCheck <N> <pasta>"
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
                "Erro: N deve ser um inteiro positivo."
            );
            return;
        }

        Path pasta = Paths.get(args[1]);

        if (!Files.isDirectory(pasta)) {

            System.out.println(
                "Erro: pasta inválida: " + pasta
            );
            return;
        }

        List<Evento> todos = new ArrayList<>();

        boolean ficheirosCompletos = true;

        System.out.println(
            "========== CAUSAL CHECK =========="
        );

        System.out.println(
            "Número de partições: " + N
        );

        System.out.println(
            "Pasta: " + pasta.toAbsolutePath()
        );

        // 1. Ler os ficheiros dos líderes
        for (int k = 0; k < N; k++) {

            Path caminho = pasta.resolve(
                "lider-p" + k + ".txt"
            );

            System.out.println();
            System.out.println(
                "----- PARTIÇÃO p" + k + " -----"
            );

            if (!Files.isRegularFile(caminho)) {

                System.out.println(
                    "ERRO: ficheiro não encontrado: "
                    + caminho.getFileName()
                );

                ficheirosCompletos = false;
                continue;
            }

            try {

                long anterioresV = violacoesV;
                long anterioresInvalidos = registosInvalidos;

                List<Evento> eventos = lerParticao(
                    caminho, k, N
                );

                todos.addAll(eventos);

                System.out.println(
                    "Registos lidos: " + eventos.size()
                );

                if (violacoesV == anterioresV
                        && registosInvalidos == anterioresInvalidos) {

                    System.out.println(
                        "Verificação V[" + k
                        + "] == seq: OK"
                    );

                } else {

                    System.out.println(
                        "Verificação V[" + k
                        + "] == seq: ERRO"
                    );
                }

            } catch (IOException e) {

                System.out.println(
                    "Erro ao ler ficheiro: "
                    + e.getMessage()
                );

                ficheirosCompletos = false;
            }
        }

        // 2. Comparar pares de partições diferentes
        long aAntesB = 0;
        long bAntesA = 0;
        long concorrentes = 0;
        long concorrentesLDistinto = 0;
        long totalPares = 0;

        for (int i = 0; i < todos.size(); i++) {

            Evento a = todos.get(i);

            for (int j = i + 1; j < todos.size(); j++) {

                Evento b = todos.get(j);

                // Comparar apenas partições diferentes
                if (a.k == b.k) {
                    continue;
                }

                totalPares++;

                // Comparação através da API VectorClock
                var relacao = a.r.getVector().compare(
                    b.r.getVector()
                );

                switch (relacao) {

                    case BEFORE:

                        // a acontece antes de b
                        aAntesB++;

                        // Verificar L(a) < L(b)
                        if (a.r.getLamport()
                                >= b.r.getLamport()) {

                            violacoesLamport++;

                            System.out.println(
                                "VIOLAÇÃO LAMPORT: p"
                                + a.k + "#" + a.r.getSeq()
                                + " → p"
                                + b.k + "#" + b.r.getSeq()
                                + " mas L(a)="
                                + a.r.getLamport()
                                + " >= L(b)="
                                + b.r.getLamport()
                            );
                        }
                        break;

                    case AFTER:

                        // b acontece antes de a
                        bAntesA++;

                        // Verificar L(b) < L(a)
                        if (b.r.getLamport()
                                >= a.r.getLamport()) {

                            violacoesLamport++;

                            System.out.println(
                                "VIOLAÇÃO LAMPORT: p"
                                + b.k + "#" + b.r.getSeq()
                                + " → p"
                                + a.k + "#" + a.r.getSeq()
                                + " mas L(b)="
                                + b.r.getLamport()
                                + " >= L(a)="
                                + a.r.getLamport()
                            );
                        }
                        break;

                    case CONCURRENT:

                        // Eventos concorrentes
                        concorrentes++;

                        if (a.r.getLamport()
                                != b.r.getLamport()) {

                            concorrentesLDistinto++;
                        }
                        break;

                    case EQUAL:

                        // Vetores iguais em partições diferentes
                        // Não são contabilizados como concorrentes
                        anomaliasIguais++;

                        System.out.println(
                            "ANOMALIA VETORES IGUAIS: "
                            + "p" + a.k + "#" + a.r.getSeq()
                            + " e p" + b.k + "#" + b.r.getSeq()
                            + " V=" + a.r.getVector()
                        );
                        break;
                }
            }
        }

        // 3. Ordenar por Lamport e partição
        todos.sort(
            Comparator
                .comparingLong(
                    (Evento e) -> e.r.getLamport()
                )
                .thenComparingInt(e -> e.k)
        );

        // 4. Escrever vista-lamport.txt
        Path vista = pasta.resolve(
            "vista-lamport.txt"
        );

        boolean vistaCriada = false;

        try {

            List<String> linhas = new ArrayList<>();

            for (Evento e : todos) {

                // Identificar a partição de origem
                linhas.add(
                    "p" + e.k + ";"
                    + e.r.toLine()
                );
            }

            Files.write(
                vista,
                linhas,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );

            vistaCriada = true;

        } catch (IOException e) {

            System.out.println(
                "ERRO ao criar vista-lamport.txt: "
                + e.getMessage()
            );
        }

        // 5. Relatório final
        System.out.println();
        System.out.println(
            "========== RELATÓRIO GLOBAL =========="
        );

        System.out.println(
            "Registos analisados: " + todos.size()
        );

        System.out.println(
            "Pares de partições diferentes: "
            + totalPares
        );

        System.out.println();
        System.out.println(
            "Classificação dos pares:"
        );

        System.out.println(
            "  a → b (BEFORE): " + aAntesB
        );

        System.out.println(
            "  b → a (AFTER): " + bAntesA
        );

        System.out.println(
            "  Concorrentes (CONCURRENT): "
            + concorrentes
        );

        System.out.println(
            "  Anomalias de vetores iguais (EQUAL): "
            + anomaliasIguais
        );

        System.out.println();
        System.out.println(
            "Verificação dos relógios:"
        );

        System.out.println(
            "  Violações V[k] == seq: "
            + violacoesV
        );

        System.out.println(
            "  Violações de Lamport: "
            + violacoesLamport
        );

        System.out.println(
            "  Registos inválidos: "
            + registosInvalidos
        );

        System.out.println();
        System.out.println(
            "Concorrência:"
        );

        System.out.println(
            "  Pares concorrentes com L distinto: "
            + concorrentesLDistinto
        );

        // Evitar divisão por zero
        if (concorrentes == 0) {

            System.out.println(
                "  Sem pares concorrentes; "
                + "proporção não aplicável"
            );

        } else {

            double proporcao =
                    100.0 * concorrentesLDistinto
                    / concorrentes;

            System.out.printf(
                java.util.Locale.US,
                "  Proporção: %.2f%%%n",
                proporcao
            );
        }

        System.out.println();
        System.out.println(
            "Vista Lamport: "
            + (vistaCriada
                ? vista.toAbsolutePath()
                : "não criada")
        );

        // 6. Verificação global
        boolean valido =
                ficheirosCompletos
                && registosInvalidos == 0
                && violacoesV == 0
                && violacoesLamport == 0
                && anomaliasIguais == 0
                && vistaCriada;

        System.out.println();
        System.out.println(
            "Resultado geral: "
            + (valido ? "OK" : "ERROS DETETADOS")
        );

        System.out.println(
            "======================================"
        );
    }
}
