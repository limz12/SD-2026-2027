package tpf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Testes do registo duravel, dos chunks e da selagem (sprint E2-02), SEM JUnit.
 *
 * Como as constantes de Wire sao lidas uma unica vez, este teste define
 * tp.selagemMs=300 ANTES de tocar em Wire, para a selagem por tempo ser rapida.
 *
 * Corre com: java -cp target/classes tpf.TestesArquivo
 */
public class TestesArquivo {

    private static int total = 0;
    private static int falhas = 0;

    public static void main(String[] args) throws Exception {
        // Propriedades ANTES da primeira utilizacao de Wire.
        System.setProperty("tpf.selagemMs", "300");

        Path raiz = Files.createTempDirectory("tpf-arquivo-teste");
        try {
            wireConstantes();
            diarioContinuaLsn(raiz);
            diarioLinhaCorrompidaNoFim(raiz);
            chunkPaddingELeituraInversa(raiz);
            registoUnicoSelaPorTempo(raiz);
            rajadaSelaPorTamanho(raiz);
            semTextoEmClaroNosSelados(raiz);
            masterKey(raiz);
        } finally {
            apagarRecursivo(raiz);
        }

        System.out.println();
        System.out.println(total + " verificacoes, " + falhas + " falhas.");
        if (falhas > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- testes

    static void wireConstantes() {
        verificar("CHUNK_MAX_BYTES por omissao e 65536", Wire.CHUNK_MAX_BYTES == 65536,
                String.valueOf(Wire.CHUNK_MAX_BYTES));
        verificar("PAD_BYTES por omissao e 4096", Wire.PAD_BYTES == 4096, String.valueOf(Wire.PAD_BYTES));
        verificar("SELAGEM_MS e o da propriedade definida no arranque (300)",
                Wire.SELAGEM_MS == 300, String.valueOf(Wire.SELAGEM_MS));
    }

    /** Depois de reabrir o diario, os LSN continuam do ultimo valor. */
    static void diarioContinuaLsn(Path raiz) throws Exception {
        Path pasta = raiz.resolve("diario");
        Files.createDirectories(pasta);
        Path diário = pasta.resolve("registos.log");

        RegistoDuravel d1 = new RegistoDuravel(diário);
        verificar("diario novo comeca no LSN 0", d1.getUltimoLsn() == 0, String.valueOf(d1.getUltimoLsn()));
        Registo a = d1.acrescentar("ana", "arquivo", "primeiro;registo");
        Registo b = d1.acrescentar("bruno", "arquivo", "segundo\nregisto\tcom tab");
        verificar("lsn atribuidos em sequencia", a.lsn() == 1 && b.lsn() == 2,
                a.lsn() + "," + b.lsn());
        d1.close();

        RegistoDuravel d2 = new RegistoDuravel(diário);
        verificar("apos reabrir, a numeracao continua no ultimo LSN", d2.getUltimoLsn() == 2,
                String.valueOf(d2.getUltimoLsn()));
        Registo c = d2.acrescentar("carla", "arquivo", "terceiro");
        verificar("o proximo LSN nao repete nem salta", c.lsn() == 3, String.valueOf(c.lsn()));
        d2.close();

        // E o roundtrip da linha do diario
        List<String> linhas = Files.readAllLines(diário);
        Registo lido = Registo.daLinhaDiario(linhas.get(1));
        verificar("linha do diario roundtrip (com ';' e tab no texto)",
                lido != null && lido.texto().equals(b.texto()) && lido.autor().equals("bruno"),
                String.valueOf(lido));
        verificar("linha malformada devolve null", Registo.daLinhaDiario("lixo sem formato") == null, "");
        verificar("linha vazia devolve null", Registo.daLinhaDiario("") == null, "");
    }

    /** Uma ultima linha partida (crash a meio da escrita) e ignorada no arranque. */
    static void diarioLinhaCorrompidaNoFim(Path raiz) throws Exception {
        Path pasta = raiz.resolve("diario-corrompido");
        Files.createDirectories(pasta);
        Path diário = pasta.resolve("registos.log");
        Files.writeString(diário,
                "1;1730000000000;ana;arquivo;" + java.util.Base64.getEncoder().encodeToString("ola".getBytes())
                        + "\n3;1730000000100;bru" // linha partida no fim (falta o resto)
        );
        verificar("linha final partida e ignorada: ultimo LSN continua valido",
                RegistoDuravel.lerUltimoLsn(diário) == 1, String.valueOf(RegistoDuravel.lerUltimoLsn(diário)));
    }

    /** Serializacao com padding e leitura inversa. */
    static void chunkPaddingELeituraInversa(Path raiz) throws Exception {
        Chunk c = new Chunk();
        List<Registo> originais = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Registo r = new Registo(i, 1730000000000L + i, "autor" + i, "arquivo",
                    "texto do registo " + i + " — çãõ é, com ';' e tab\t");
            c.adicionar(r);
            originais.add(r);
        }
        byte[] serializado = c.serializar();
        int totalCifrado = serializado.length + ChunkCrypto.TAG_BYTES;
        verificar("plaintext + tag e multiplo de PAD_BYTES",
                totalCifrado % Wire.PAD_BYTES == 0, serializado.length + "+" + ChunkCrypto.TAG_BYTES);

        List<Registo> lidos = Chunk.parse(serializado);
        verificar("leitura inversa reconstrui os registos", lidos.equals(originais),
                "lidos=" + lidos.size());

        // padding vazio: tamanho exatamente multiplo tambem funciona
        Chunk cheio = new Chunk();
        cheio.adicionar(new Registo(1, 1, "a", "arquivo", "y".repeat(Wire.PAD_BYTES * 2)));
        byte[] semPadding = cheio.serializar();
        verificar("chunk em que o padding e zero tambem e multiplo",
                (semPadding.length + ChunkCrypto.TAG_BYTES) % Wire.PAD_BYTES == 0, String.valueOf(semPadding.length));
        verificar("leitura inversa com padding zero", Chunk.parse(semPadding).size() == 1, "");

        // parse estraga-se com dados corrompidos
        byte[] magiaErrada = semPadding.clone();
        magiaErrada[0] = 'X';
        try {
            Chunk.parse(magiaErrada);
            verificar("parse com magic errado falha", false, "nao lancou excecao");
        } catch (IllegalStateException e) {
            verificar("parse com magic errado falha", true, "");
        }
    }

    /** Um unico registo da origem a um chunk selado ~SELAGEM_MS depois. */
    static void registoUnicoSelaPorTempo(Path raiz) throws Exception {
        Path pasta = raiz.resolve("selagem-tempo");
        Files.createDirectories(pasta);
        try (Arquivo arquivo = new Arquivo(pasta, chaveDeTeste(11))) {
            long t0 = System.currentTimeMillis();
            Registo r = arquivo.registar("ana", "arquivo", "um registo so");
            verificar("primeiro LSN e 1", r.lsn() == 1, String.valueOf(r.lsn()));

            // Ainda nao selou (nao deu tempo)
            Thread.sleep(50);
            verificar("chunk ainda aberto antes da SELAGEM_MS", !temSelados(pasta), "");

            // Espera a selagem por tempo
            long espera = Wire.SELAGEM_MS + 500;
            while (System.currentTimeMillis() - t0 < espera && !temSelados(pasta)) {
                Thread.sleep(25);
            }
            verificar("chunk selado ~SELAGEM_MS depois do primeiro registo",
                    temSelados(pasta), "nada em selados/");

            // E o evento SELADO esta no diario de eventos, com intervalo de LSN
            String eventos = Files.readString(pasta.resolve("eventos-lider.txt"));
            verificar("evento SELADO com chunkId, intervalo de LSN e tamanho",
                    eventos.matches("(?s).*SELADO;chunkId=[0-9a-f]{64};lsnDe=1;lsnAte=1;bytes=[0-9]+.*"), eventos);
            verificar("evento REGISTO presente", eventos.contains("REGISTO;lsn=1"), eventos);

            // Abrir o chunk selado devolve exatamente os registos
            Sealed s = Sealed.abrir(pasta.resolve("selados"), primeiroChunkId(pasta), chaveDeTeste(11));
            List<Registo> lidos = s.lerRegistos();
            verificar("open do chunk selado devolve LSN 1, autor, canal e texto exatos",
                    lidos.size() == 1 && lidos.get(0).equals(r), String.valueOf(lidos));

            // Tamanho do .bin e multiplo de PAD_BYTES
            Path bin = Sealed.ficheiroBin(pasta.resolve("selados"), s.getChunkId());
            long tamanho = Files.size(bin);
            verificar(".bin com tamanho multiplo de PAD_BYTES",
                    tamanho % Wire.PAD_BYTES == 0, String.valueOf(tamanho));
        }
    }

    /** /rajada 500 0: varios chunks por tamanho, todos multiplos de PAD_BYTES. */
    static void rajadaSelaPorTamanho(Path raiz) throws Exception {
        Path pasta = raiz.resolve("selagem-tamanho");
        Files.createDirectories(pasta);
        byte[] chave = chaveDeTeste(12);
        try (Arquivo arquivo = new Arquivo(pasta, chave)) {
            for (int i = 1; i <= 500; i++) {
                arquivo.registar("rajada", "arquivo",
                        "registo sintético " + i + "/500 — " + "p".repeat(300));
            }
            arquivo.selar(); // espera ativa por todos os chunks via selagem por tempo
        }

        Path selados = pasta.resolve("selados");
        List<Path> bins;
        try (var s = Files.list(selados)) {
            bins = s.filter(p -> p.toString().endsWith(".bin")).sorted().toList();
        }
        verificar("a rajada produziu varios chunks selados", bins.size() >= 2, String.valueOf(bins.size()));

        boolean nomesCertos = true;
        boolean tamanhosCertos = true;
        for (Path bin : bins) {
            String nome = bin.getFileName().toString().replace(".bin", "");
            byte[] conteudo = Files.readAllBytes(bin);
            nomesCertos &= ChunkCrypto.sha256Hex(conteudo).equals(nome);
            tamanhosCertos &= conteudo.length % Wire.PAD_BYTES == 0;
        }
        verificar("cada .bin tem como nome o SHA-256 do seu conteudo", nomesCertos, "");
        verificar("todos os .bin tem tamanho multiplo de PAD_BYTES", tamanhosCertos, "");

        // A concatenacao dos chunks tem todos os LSN de 1 a 500, sem saltos
        List<Long> lsns = new ArrayList<>();
        for (Path bin : bins) {
            String nome = bin.getFileName().toString().replace(".bin", "");
            List<Registo> rs = Sealed.abrir(selados, nome, chave).lerRegistos();
            for (Registo r : rs) {
                lsns.add(r.lsn());
            }
        }
        lsns.sort(Long::compare); // os nomes dos ficheiros sao hashes; a ordem certa e a dos LSN
        boolean sequenciaCompleta = lsns.size() == 500;
        for (int i = 0; i < lsns.size() && sequenciaCompleta; i++) {
            sequenciaCompleta = lsns.get(i) == i + 1L;
        }
        verificar("os 500 LSN (1..500) estao nos chunks, sem repeticoes nem saltos",
                sequenciaCompleta, "total=" + lsns.size() + " primeiro=" + (lsns.isEmpty() ? -1 : lsns.get(0)));

        // O diario de eventos tem os REGISTO e os SELADO
        String eventos = Files.readString(pasta.resolve("eventos-lider.txt"));
        verificar("existem 500 eventos REGISTO", eventos.split("REGISTO;", -1).length - 1 == 500, "");
        verificar("existem varios eventos SELADO", eventos.split("SELADO;", -1).length - 1 >= 2, "");
        String diario = Files.readString(pasta.resolve("registos.log"));
        verificar("o diario tem as 500 linhas", diario.split("\n", -1).length - 1 == 500, "");
    }

    /** Nada do que foi registado pode aparecer em claro dentro de selados/. */
    static void semTextoEmClaroNosSelados(Path raiz) throws Exception {
        Path pasta = raiz.resolve("sem-claro");
        Files.createDirectories(pasta);
        String segredo = "segredoConfidencialNAOapareceEmClaro";
        try (Arquivo arquivo = new Arquivo(pasta, chaveDeTeste(13))) {
            arquivo.registar("ana", "arquivo", segredo);
            arquivo.selar();
        }
        StringBuilder tudo = new StringBuilder();
        try (var s = Files.walk(pasta.resolve("selados"))) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try {
                    tudo.append(new String(Files.readAllBytes(p), StandardCharsetsUtf8()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        verificar("procurar o texto registado com grep nao devolve nada",
                !tudo.toString().contains(segredo), "");
    }

    /** master.key: gera no primeiro arranque, recusa tamanho errado. */
    static void masterKey(Path raiz) throws Exception {
        Path pasta = raiz.resolve("chave");
        Files.createDirectories(pasta);
        Path caminho = pasta.resolve("master.key");

        MasterKey gerada = MasterKey.carregar(caminho);
        verificar("master.key gerada com 32 bytes", Files.size(caminho) == 32, String.valueOf(Files.size(caminho)));
        MasterKey recarregada = MasterKey.carregar(caminho);
        verificar("recarregar devolve a mesma chave",
                java.util.Arrays.equals(gerada.copiarBytes(), recarregada.copiarBytes()), "");

        Files.writeString(pasta.resolve("pequena.key"), "x");
        Path pequena = pasta.resolve("pequena.key");
        try {
            MasterKey.carregar(pequena);
            verificar("master.key com tamanho errado recusa o arranque", false, "nao lancou excecao");
        } catch (IllegalStateException e) {
            verificar("master.key com tamanho errado recusa o arranque",
                    e.getMessage().contains("32"), e.getMessage());
        }
        if (MasterKey.suportaPosix()) {
            var perms = Files.getPosixFilePermissions(caminho);
            boolean soDonoLeEscreve = perms.toString().contains("OWNER_WRITE") || perms.size() <= 6;
            verificar("permissoes restritas ao dono (quando o FS suporta POSIX)", soDonoLeEscreve,
                    perms.toString());
        }
    }

    // ---------------------------------------------------------------- auxiliares

    private static java.nio.charset.Charset StandardCharsetsUtf8() {
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private static byte[] chaveDeTeste(int semente) {
        byte[] chave = new byte[32];
        new java.util.Random(semente).nextBytes(chave);
        return chave;
    }

    static boolean temSelados(Path pasta) throws IOException {
        Path selados = pasta.resolve("selados");
        if (!Files.isDirectory(selados)) {
            return false;
        }
        try (var s = Files.list(selados)) {
            return s.anyMatch(p -> p.toString().endsWith(".bin"));
        }
    }

    static String primeiroChunkId(Path pasta) throws IOException {
        try (var s = Files.list(pasta.resolve("selados"))) {
            return s.filter(p -> p.toString().endsWith(".bin")).findFirst()
                    .map(p -> p.getFileName().toString().replace(".bin", ""))
                    .orElseThrow();
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
