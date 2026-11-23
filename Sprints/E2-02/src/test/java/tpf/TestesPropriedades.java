package tpf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Confirma que as constantes de Wire vieram MESMO das propriedades -Dtpf.*
 * passadas a JVM (e nao dos valores por omissao), e que uma rajada com estes
 * valores produz chunks com o tamanho certo.
 *
 * Correr com -Dtpf.padBytes=512 -Dtpf.chunkMaxBytes=8192 -Dtpf.selagemMs=200:
 *   java -Dtpf.padBytes=512 -Dtpf.chunkMaxBytes=8192 -Dtpf.selagemMs=200 ^
 *        -cp "target/test-classes;target/classes" tpf.TestesPropriedades
 *
 * Com valores invalidos (ex.: -Dtpf.padBytes=100) a JVM tem de morrer no
 * arranque da classe Wire.
 */
public class TestesPropriedades {

    private static int total = 0;
    private static int falhas = 0;

    public static void main(String[] args) throws Exception {
        int padEsperado = Integer.parseInt(System.getProperty("tpf.padBytes", "4096"));
        int maxEsperado = Integer.parseInt(System.getProperty("tpf.chunkMaxBytes", "65536"));
        long selEsperado = Long.parseLong(System.getProperty("tpf.selagemMs", "5000"));

        verificar("PAD_BYTES veio da propriedade -Dtpf.padBytes",
                Wire.PAD_BYTES == padEsperado, Wire.PAD_BYTES + " vs " + padEsperado);
        verificar("CHUNK_MAX_BYTES veio da propriedade -Dtpf.chunkMaxBytes",
                Wire.CHUNK_MAX_BYTES == maxEsperado, Wire.CHUNK_MAX_BYTES + " vs " + maxEsperado);
        verificar("SELAGEM_MS veio da propriedade -Dtpf.selagemMs",
                Wire.SELAGEM_MS == selEsperado, Wire.SELAGEM_MS + " vs " + selEsperado);
        verificar("os valores NAO sao os por omissao (a JVM tem de ter as -Dtpf.*)",
                padEsperado != 4096 && maxEsperado != 65536 && selEsperado != 5000,
                padEsperado + "/" + maxEsperado + "/" + selEsperado);

        Path raiz = Files.createTempDirectory("tpf-props-teste");
        try {
            Path pasta = raiz.resolve("dados");
            Files.createDirectories(pasta);
            byte[] chave = new byte[32];
            new java.util.Random(31).nextBytes(chave);
            try (Arquivo arquivo = new Arquivo(pasta, chave)) {
                for (int i = 1; i <= 60; i++) {
                    arquivo.registar("rajada", "arquivo", "registo sintético " + i + "/60 — " + "p".repeat(300));
                }
                arquivo.selar();
            }
            Path selados = pasta.resolve("selados");
            int nChunks = 0;
            try (var s = Files.list(selados)) {
                for (Path bin : s.filter(p -> p.toString().endsWith(".bin")).toList()) {
                    nChunks++;
                    long tamanho = Files.size(bin);
                    verificar("chunk " + nChunks + ": tamanho multiplo de PAD_BYTES (" + padEsperado + ")",
                            tamanho % padEsperado == 0, String.valueOf(tamanho));
                    verificar("chunk " + nChunks + ": tamanho dentro de CHUNK_MAX_BYTES (" + maxEsperado + ")",
                            tamanho <= maxEsperado, String.valueOf(tamanho));
                }
            }
            verificar("com chunks pequenos a rajada produziu varios chunks", nChunks >= 2,
                    String.valueOf(nChunks));
        } finally {
            apagarRecursivo(raiz);
        }

        System.out.println();
        System.out.println(total + " verificacoes, " + falhas + " falhas.");
        if (falhas > 0) {
            System.exit(1);
        }
    }

    static void verificar(String nome, boolean passou, String detalhe) {
        total++;
        if (!passou) {
            falhas++;
        }
        System.out.println((passou ? "  ok   " : "  FALHOU ") + nome + (passou ? "" : "  -> " + detalhe));
    }

    static void apagarRecursivo(Path raiz) throws Exception {
        if (!Files.isDirectory(raiz)) {
            return;
        }
        try (var s = Files.list(raiz)) {
            for (Path p : s.toList()) {
                if (Files.isDirectory(p)) {
                    apagarRecursivo(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.deleteIfExists(raiz);
    }
}
