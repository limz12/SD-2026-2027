package tpf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;

/**
 * Testes da cifra (sprint E2-02, objetivo 5), SEM JUnit:
 *
 *   1) cifrar e decifrar: o que sai do open sao exatamente os registos de entrada
 *   2) byte alterado no texto cifrado -> open falha com excecao
 *   3) salt / nonce / keyVersion diferentes -> open falha com excecao
 *   4) master.key diferente -> open falha com excecao
 *
 * Mais um vetor oficial do HKDF (RFC 5869, Test Case 1) para confirmar que a
 * derivacao da DEK segue a norma. Corre com: java -cp target/classes tpf.TestesCifra
 */
public class TestesCifra {

    private static int total = 0;
    private static int falhas = 0;

    public static void main(String[] args) throws Exception {
        Path raiz = Files.createTempDirectory("tpf-cifra-teste");
        try {
            hkdfVetorOficial();
            cifrarEDecifrar(raiz);
            byteAlteradoNoCifrado(raiz);
            saltENonceEKeyVersionDiferentes(raiz);
            masterKeyDiferente(raiz);
            chunkIdENomeDoFicheiro(raiz);
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

    /** HKDF-SHA256 tem de dar o vetor oficial do RFC 5869 (Test Case 1). */
    static void hkdfVetorOficial() throws Exception {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = ChunkCrypto.hkdfSha256(ikm, salt, info, 32);
        String esperado = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf";
        verificar("HKDF-SHA256 da o vetor oficial do RFC 5869",
                HexFormat.of().formatHex(okm).equals(esperado), HexFormat.of().formatHex(okm));

        byte[] salt2 = salt.clone();
        salt2[0] ^= 0x01;
        verificar("salt diferente da DEK diferente",
                !java.util.Arrays.equals(okm, ChunkCrypto.hkdfSha256(ikm, salt2, info, 32)), "");
    }

    /** Cifrar e decifrar: roundtrip completo via ficheiros .bin/.hdr. */
    static void cifrarEDecifrar(Path raiz) throws Exception {
        byte[] chave = chaveDeTeste(1);
        List<Registo> originais = List.of(
                new Registo(1, 1730000000000L, "ana", "arquivo", "primeiro registo"),
                new Registo(2, 1730000000001L, "bruno", "arquivo", "texto com acentos çãõ é e ; ponto e vírgula"),
                new Registo(3, 1730000000002L, "ana", "arquivo", "x".repeat(2000)));
        byte[] plaintext = chunkComRegistos(originais);

        Path pasta = raiz.resolve("roundtrip");
        Sealed cifrado = Sealed.cifrar(chave, plaintext);
        cifrado.gravar(pasta);

        Sealed aberto = Sealed.abrir(pasta, cifrado.getChunkId(), chave);
        List<Registo> lidos = aberto.lerRegistos();
        verificar("open devolve exatamente os registos cifrados", lidos.equals(originais),
                "lidos=" + lidos.size());

        verificar("o .bin NAO tem texto em claro",
                !new String(Files.readAllBytes(Sealed.ficheiroBin(pasta, cifrado.getChunkId())),
                        StandardCharsets.ISO_8859_1).contains("primeiro registo"), "");

        verificar("o .hdr tem keyVersion;salt;nonce em Base64",
                Files.readString(Sealed.ficheiroHdr(pasta, cifrado.getChunkId())).trim()
                        .split(";").length == 3, "");
    }

    /** Alterar um byte do .bin tem de fazer o open falhar. */
    static void byteAlteradoNoCifrado(Path raiz) throws Exception {
        byte[] chave = chaveDeTeste(2);
        Path pasta = raiz.resolve("byte-alterado");
        Sealed cifrado = gravarChunkDeTeste(pasta, chave, 1);

        Path bin = Sealed.ficheiroBin(pasta, cifrado.getChunkId());
        byte[] conteudo = Files.readAllBytes(bin);

        // Altera um byte no meio do texto cifrado (dentro do ciphertext, fora da tag).
        byte[] corrompido = conteudo.clone();
        int meio = corrompido.length / 2;
        corrompido[meio] ^= 0x01;
        Files.write(bin, corrompido);
        verificar("byte alterado no meio: open falha",
                lancaSeguranca(() -> Sealed.abrir(pasta, cifrado.getChunkId(), chave)), "GCM");

        // Repõe e altera um byte da tag (fim do ficheiro).
        byte[] tag = conteudo.clone();
        tag[tag.length - 1] ^= 0x01;
        Files.write(bin, tag);
        verificar("byte alterado na tag: open falha",
                lancaSeguranca(() -> Sealed.abrir(pasta, cifrado.getChunkId(), chave)), "GCM");

        // E confirma que o ficheiro original continua a abrir.
        Files.write(bin, conteudo);
        Sealed.abrir(pasta, cifrado.getChunkId(), chave);
        verificar("ficheiro reposto: open volta a funcionar", true, "");
    }

    /** Salt, nonce ou keyVersion diferentes no .hdr fazem o open falhar. */
    static void saltENonceEKeyVersionDiferentes(Path raiz) throws Exception {
        byte[] chave = chaveDeTeste(3);
        Path pasta = raiz.resolve("parametros-diferentes");
        Sealed cifrado = gravarChunkDeTeste(pasta, chave, 1);

        Path bin = Sealed.ficheiroBin(pasta, cifrado.getChunkId());
        Path hdr = Sealed.ficheiroHdr(pasta, cifrado.getChunkId());
        byte[] ciphertextOriginal = Files.readAllBytes(bin);
        String hdrOriginal = Files.readString(hdr);

        // salt diferente
        byte[] saltNovo = new byte[ChunkCrypto.SALT_BYTES];
        new java.security.SecureRandom().nextBytes(saltNovo);
        escreverHdr(hdr, "1;" + ChunkCrypto.base64(saltNovo) + ";" + ultimoCampo(hdrOriginal));
        verificar("salt diferente: open falha",
                lancaSeguranca(() -> Sealed.abrir(pasta, cifrado.getChunkId(), chave)), "GCM");

        // nonce diferente
        byte[] nonceNovo = new byte[ChunkCrypto.NONCE_BYTES];
        new java.security.SecureRandom().nextBytes(nonceNovo);
        escreverHdr(hdr, "1;" + penultimoCampo(hdrOriginal) + ";" + ChunkCrypto.base64(nonceNovo));
        verificar("nonce diferente: open falha",
                lancaSeguranca(() -> Sealed.abrir(pasta, cifrado.getChunkId(), chave)), "GCM");

        // keyVersion diferente (falha ANTES da cifra, pela ordem das verificacoes)
        escreverHdr(hdr, "2;" + penultimoCampo(hdrOriginal) + ";" + ultimoCampo(hdrOriginal));
        try {
            Sealed.abrir(pasta, cifrado.getChunkId(), chave);
            verificar("keyVersion diferente: open falha", false, "nao lancou excecao");
        } catch (GeneralSecurityException e) {
            verificar("keyVersion diferente: open falha",
                    e.getMessage().contains("Versao de chave"), e.getMessage());
        }

        // repoe e confirma
        escreverHdr(hdr, hdrOriginal.trim());
        Files.write(bin, ciphertextOriginal);
        Sealed.abrir(pasta, cifrado.getChunkId(), chave);
        verificar("parametros repostos: open funciona", true, "");
    }

    /** Uma master.key diferente tambem faz o open falhar (autenticacao GCM). */
    static void masterKeyDiferente(Path raiz) throws Exception {
        byte[] chaveCerta = chaveDeTeste(4);
        Path pasta = raiz.resolve("chave-diferente");
        Sealed cifrado = gravarChunkDeTeste(pasta, chaveCerta, 1);

        byte[] chaveErrada = chaveDeTeste(5);
        try {
            Sealed.abrir(pasta, cifrado.getChunkId(), chaveErrada);
            verificar("master.key diferente: open falha", false, "nao lancou excecao");
        } catch (GeneralSecurityException e) {
            verificar("master.key diferente: open falha", true, "");
        }
        Sealed.abrir(pasta, cifrado.getChunkId(), chaveCerta);
        verificar("master.key certa: open funciona", true, "");

        // O nome do ficheiro e o SHA-256 do conteudo cifrado
        byte[] conteudo = Files.readAllBytes(Sealed.ficheiroBin(pasta, cifrado.getChunkId()));
        verificar("chunkId = SHA-256 do texto cifrado (incl. tag)",
                ChunkCrypto.sha256Hex(conteudo).equals(cifrado.getChunkId()), "");
    }

    static void chunkIdENomeDoFicheiro(Path raiz) throws Exception {
        byte[] chave = chaveDeTeste(6);
        Path pasta = raiz.resolve("ids");
        Sealed a = gravarChunkDeTeste(pasta, chave, 1);
        Sealed b = gravarChunkDeTeste(pasta, chave, 1); // mesmo plaintext, salt/nonce novos
        verificar("chunks com o mesmo plaintext tem ids diferentes (salt/nonce novos)",
                !a.getChunkId().equals(b.getChunkId()), "");
        verificar("chunkId tem 64 caracteres hex (SHA-256)",
                a.getChunkId().matches("[0-9a-f]{64}"), a.getChunkId());
    }

    // ---------------------------------------------------------------- auxiliares

    private static byte[] chaveDeTeste(int semente) {
        byte[] chave = new byte[32];
        new java.util.Random(semente).nextBytes(chave); // deterministica para os testes
        return chave;
    }

    private static byte[] chunkComRegistos(List<Registo> registos) {
        Chunk c = new Chunk();
        for (Registo r : registos) {
            c.adicionar(r);
        }
        return c.serializar();
    }

    private static Sealed gravarChunkDeTeste(Path pasta, byte[] chave, int versao) throws Exception {
        byte[] plaintext = chunkComRegistos(List.of(
                new Registo(versao, 1730000000000L, "teste", "arquivo", "registo do chunk de teste " + versao)));
        Sealed s = Sealed.cifrar(chave, plaintext);
        s.gravar(pasta);
        return s;
    }

    private static void escreverHdr(Path hdr, String linha) throws Exception {
        Files.writeString(hdr, linha + "\n", StandardCharsets.UTF_8);
    }

    private static String ultimoCampo(String hdr) {
        return hdr.trim().split(";")[2];
    }

    private static String penultimoCampo(String hdr) {
        return hdr.trim().split(";")[1];
    }

    interface Acao {
        void correr() throws Exception;
    }

    static boolean lancaSeguranca(Acao a) {
        try {
            a.correr();
            return false;
        } catch (GeneralSecurityException e) {
            return true;
        } catch (Exception e) {
            return false;
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
