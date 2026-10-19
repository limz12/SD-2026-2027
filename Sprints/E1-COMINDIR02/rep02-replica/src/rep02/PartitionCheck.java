
package rep02;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PartitionCheck {

    static final int R = 2;

    // Nome dos ficheiros das réplicas
    static final Pattern REPLICA =
            Pattern.compile("replica-(.+)-p(\\d+)\\.txt");

    static class Dados {

        final Path caminho;
        final List<String> linhas = new ArrayList<>();
        final Set<String> sensores = new HashSet<>();

        boolean sequenciaValida = true;
        boolean conteudoValido = true;

        Dados(Path caminho) {
            this.caminho = caminho;
        }

        int total() {
            return linhas.size();
        }

        int sensoresDistintos() {
            return sensores.size();
        }
    }

    // Lê e valida um ficheiro de registos
    static Dados ler(Path caminho) throws IOException {

        Dados dados = new Dados(caminho);

        long esperado = 1;
        int numeroLinha = 0;

        try (BufferedReader in = Files.newBufferedReader(
                caminho, StandardCharsets.UTF_8)) {

            String linha;

            while ((linha = in.readLine()) != null) {

                numeroLinha++;

                if (linha.isBlank()) {
                    dados.conteudoValido = false;

                    System.out.println(
                        "  ERRO: linha vazia em "
                        + caminho.getFileName()
                        + ", linha " + numeroLinha
                    );
                    continue;
                }

                dados.linhas.add(linha);

                try {

                    SensorRecord r =
                            SensorRecord.fromLine(linha);

                    long seq = r.getSeq();

                    // Verificar sequência consecutiva
                    if (seq != esperado) {

                        dados.sequenciaValida = false;

                        System.out.println(
                            "  ERRO: sequência inválida em "
                            + caminho.getFileName()
                            + ", linha " + numeroLinha
                            + " (esperado " + esperado
                            + ", encontrado " + seq + ")"
                        );
                    }

                    esperado++;

                    // Formato:
                    // seq;sensor;temperatura;instante
                    String[] partes = linha.split(";", -1);

                    if (partes.length != 4
                            || partes[1].isEmpty()) {

                        dados.conteudoValido = false;

                        System.out.println(
                            "  ERRO: formato inválido em "
                            + caminho.getFileName()
                            + ", linha " + numeroLinha
                        );

                    } else {

                        dados.sensores.add(partes[1]);
                    }

                } catch (RuntimeException e) {

                    dados.conteudoValido = false;
                    dados.sequenciaValida = false;

                    System.out.println(
                        "  ERRO: registo inválido em "
                        + caminho.getFileName()
                        + ", linha " + numeroLinha
                        + ": " + e.getMessage()
                    );

                    esperado++;
                }
            }
        }

        return dados;
    }

    // Verifica se os sensores pertencem à partição
    static boolean verificarParticao(
            Dados dados,
            int k,
            Partitioner partitioner) {

        boolean valido = true;

        for (String sensor : dados.sensores) {

            int j = partitioner.of(sensor);

            if (j != k) {

                System.out.println(
                    "  PARTIÇÃO ERRADA: "
                    + sensor + " pertence a p" + j
                    + " (encontrado em p" + k + ")"
                );

                valido = false;
            }
        }

        return valido;
    }

    // Verifica se a réplica é igual ou prefixo do líder
    static String comparar(Dados lider, Dados replica) {

        int nLider = lider.total();
        int nReplica = replica.total();

        if (nReplica > nLider) {
            return "DIVERGENTE";
        }

        for (int i = 0; i < nReplica; i++) {

            if (!lider.linhas.get(i).equals(
                    replica.linhas.get(i))) {

                return "DIVERGENTE";
            }
        }

        if (nReplica == nLider) {
            return "IGUAL";
        }

        return "PREFIXO";
    }

    public static void main(String[] args) {

        // Argumentos: <N> <pasta>
        if (args.length != 2) {

            System.out.println(
                "Uso: PartitionCheck <N> <pasta>"
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

        Partitioner partitioner = new Partitioner(N);

        // Descobrir os ficheiros das réplicas
        Map<Integer, List<Path>> replicas = new HashMap<>();

        try (DirectoryStream<Path> ficheiros =
                     Files.newDirectoryStream(pasta)) {

            for (Path caminho : ficheiros) {

                if (!Files.isRegularFile(caminho)) {
                    continue;
                }

                String nome =
                        caminho.getFileName().toString();

                Matcher m = REPLICA.matcher(nome);

                if (m.matches()) {

                    int k = Integer.parseInt(m.group(2));

                    replicas.computeIfAbsent(
                        k, x -> new ArrayList<>()
                    ).add(caminho);
                }
            }

        } catch (IOException | NumberFormatException e) {

            System.out.println(
                "Erro ao procurar ficheiros: "
                + e.getMessage()
            );
            return;
        }

        // Ordenar ficheiros para um relatório estável
        for (List<Path> lista : replicas.values()) {

            lista.sort(
                Comparator.comparing(
                    p -> p.getFileName().toString()
                )
            );
        }

        // Total global único:
        // soma dos registos dos líderes de cada partição
        long totalGlobal = 0;

        boolean sistemaValido = true;
        int lideresEncontrados = 0;

        System.out.println(
            "========== PARTITION CHECK =========="
        );

        System.out.println("Partições: " + N);
        System.out.println("Pasta: " + pasta.toAbsolutePath());
        System.out.println("Réplicas esperadas: " + R);

        // Verificar cada partição
        for (int k = 0; k < N; k++) {

            System.out.println();
            System.out.println(
                "========== PARTIÇÃO p" + k + " =========="
            );

            Path caminhoLider = pasta.resolve(
                "lider-p" + k + ".txt"
            );

            if (!Files.isRegularFile(caminhoLider)) {

                System.out.println(
                    "ERRO: ficheiro do líder não encontrado: "
                    + caminhoLider.getFileName()
                );

                sistemaValido = false;
                continue;
            }

            try {

                Dados lider = ler(caminhoLider);
                lideresEncontrados++;

                System.out.println(
                    "Líder: " + caminhoLider.getFileName()
                );

                System.out.println(
                    "Registos: " + lider.total()
                );

                System.out.println(
                    "Sensores distintos: "
                    + lider.sensoresDistintos()
                );

                // Validar sequência do líder
                if (lider.sequenciaValida
                        && lider.conteudoValido) {

                    System.out.println(
                        "Sequência do líder: OK"
                    );

                } else {

                    System.out.println(
                        "Sequência/conteúdo do líder: ERRO"
                    );

                    sistemaValido = false;
                }

                // Validar distribuição dos sensores
                boolean particaoCorreta =
                        verificarParticao(
                            lider, k, partitioner
                        );

                System.out.println(
                    "Sensores na partição correta: "
                    + (particaoCorreta ? "OK" : "ERRO")
                );

                if (!particaoCorreta) {
                    sistemaValido = false;
                }

                // Acrescentar os registos desta partição
                // ao único total global
                totalGlobal += lider.total();

                // Verificar réplicas desta partição
                List<Path> lista = replicas.getOrDefault(
                    k, Collections.emptyList()
                );

                System.out.println(
                    "Réplicas encontradas: "
                    + lista.size() + " (esperadas: " + R + ")"
                );

                if (lista.size() != R) {

                    System.out.println(
                        "ERRO: número de réplicas incorreto."
                    );

                    sistemaValido = false;
                }

                for (Path caminhoReplica : lista) {

                    System.out.println();
                    System.out.println(
                        "  Réplica: "
                        + caminhoReplica.getFileName()
                    );

                    try {

                        Dados replica = ler(caminhoReplica);

                        System.out.println(
                            "  Registos: " + replica.total()
                        );

                        if (replica.sequenciaValida
                                && replica.conteudoValido) {

                            System.out.println(
                                "  Sequência: OK"
                            );

                        } else {

                            System.out.println(
                                "  Sequência/conteúdo: ERRO"
                            );

                            sistemaValido = false;
                        }

                        String resultado =
                                comparar(lider, replica);

                        // Se algum ficheiro for inválido,
                        // a comparação também é inválida
                        if (!lider.conteudoValido
                                || !lider.sequenciaValida
                                || !replica.conteudoValido
                                || !replica.sequenciaValida) {

                            resultado = "INVÁLIDO";
                        }

                        System.out.println(
                            "  Comparação com líder: "
                            + resultado
                        );

                        // Mostrar registos em falta apenas
                        // para IGUAL ou PREFIXO
                        if (resultado.equals("IGUAL")
                                || resultado.equals("PREFIXO")) {

                            int faltam = lider.total()
                                    - replica.total();

                            System.out.println(
                                "  Registos em falta: " + faltam
                            );

                        } else {

                            System.out.println(
                                "  Registos em falta: não aplicável"
                            );
                        }

                        if (resultado.equals("DIVERGENTE")
                                || resultado.equals("INVÁLIDO")) {

                            sistemaValido = false;
                        }

                        if (resultado.equals("PREFIXO")) {

                            System.out.println(
                                "  AVISO: réplica ainda não "
                                + "alcançou o líder."
                            );
                        }

                        if (replica.total() > lider.total()) {

                            System.out.println(
                                "  ERRO: réplica contém mais "
                                + "registos que o líder."
                            );

                            sistemaValido = false;
                        }

                    } catch (IOException e) {

                        System.out.println(
                            "  ERRO ao ler réplica: "
                            + e.getMessage()
                        );

                        sistemaValido = false;
                    }
                }

            } catch (IOException e) {

                System.out.println(
                    "ERRO ao ler líder: " + e.getMessage()
                );

                sistemaValido = false;
            }
        }

        // Resumo final
        System.out.println();
        System.out.println(
            "========== RESUMO GLOBAL =========="
        );

        System.out.println(
            "Partições previstas: " + N
        );

        System.out.println(
            "Ficheiros de líderes encontrados: "
            + lideresEncontrados
        );

        // Verificar que existem N líderes
        if (lideresEncontrados == N) {

            System.out.println(
                "Verificação dos N líderes: OK"
            );

        } else {

            System.out.println(
                "Verificação dos N líderes: ERRO"
            );

            sistemaValido = false;
        }

        // Total global = soma das partições
        System.out.println(
            "Total global de registos "
            + "(soma das partições): "
            + totalGlobal
        );

        System.out.println();

        System.out.println(
            "Resultado geral: "
            + (sistemaValido ? "OK" : "ERROS DETETADOS")
        );

        System.out.println(
            "==================================="
        );
    }
}
