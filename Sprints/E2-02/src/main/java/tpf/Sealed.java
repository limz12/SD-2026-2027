package tpf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;

/**
 * Um chunk selado, tal como vive na pasta selados/:
 *
 *   selados/<chunkId>.bin   -> texto cifrado (AES-256-GCM), com a tag incluida
 *   selados/<chunkId>.hdr   -> uma linha "keyVersion;salt;nonce", em Base64
 *
 * O nome do ficheiro e o SHA-256 (hex) do conteudo do .bin, incluindo a tag.
 * So o lider, com a master.key certa, consegue abrir.
 *
 * abrir() verifica POR ESTA ORDEM (qualquer falha lança exceção):
 *   1) o SHA-256 do .bin corresponde ao chunkId (integridade do ficheiro/nome)
 *   2) a versao da chave no .hdr e a que conhecemos (keyVersion = 1)
 *   3) a autenticacao GCM (chave/salt/nonce/aad certos e sem alteracoes)
 */
public final class Sealed {

    private final String chunkId;
    private final int keyVersion;
    private final byte[] salt;
    private final byte[] nonce;
    private final byte[] ciphertext; // o que vive no .bin (tag incluida)
    private final byte[] plaintext;  // so existe em memoria no lider, nunca vai para o disco em claro

    private Sealed(String chunkId, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext, byte[] plaintext) {
        this.chunkId = chunkId;
        this.keyVersion = keyVersion;
        this.salt = salt;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
        this.plaintext = plaintext;
    }

    /** Cifra o plaintext de um chunk e prepara-o para gravacao. */
    public static Sealed cifrar(byte[] chaveMestra, byte[] plaintext) throws GeneralSecurityException {
        ChunkCrypto.ChunkCifrado c = ChunkCrypto.cifrar(chaveMestra, plaintext);
        return new Sealed(c.chunkId(), c.keyVersion(), c.salt(), c.nonce(), c.ciphertext(), plaintext);
    }

    /** Grava <chunkId>.bin e <chunkId>.hdr na pasta dada (cria-a se faltar). */
    public void gravar(Path pastaSelados) throws IOException {
        exigirChunkIdValido(chunkId);
        Files.createDirectories(pastaSelados);
        Path bin = ficheiroBin(pastaSelados, chunkId);
        Path hdr = ficheiroHdr(pastaSelados, chunkId);
        if (Files.exists(bin) || Files.exists(hdr)) {
            throw new IOException("Ja existe um chunk com o id " + chunkId + " (colisao de nomes improvavel).");
        }
        Files.write(bin, ciphertext, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        String linha = keyVersion + ";" + ChunkCrypto.base64(salt) + ";" + ChunkCrypto.base64(nonce) + "\n";
        Files.writeString(hdr, linha, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * Abre um chunk selado da pasta: le .hdr e .bin, verifica nesta ordem
     * (1) hash contra o id, (2) versao da chave, (3) autenticacao GCM, e
     * prepara a leitura dos registos.
     */
    public static Sealed abrir(Path pastaSelados, String chunkId, byte[] chaveMestra)
            throws IOException, GeneralSecurityException {
        exigirChunkIdValido(chunkId);
        Path bin = ficheiroBin(pastaSelados, chunkId);
        Path hdr = ficheiroHdr(pastaSelados, chunkId);

        byte[] ciphertext = Files.readAllBytes(bin);
        String linhaHdr = Files.readString(hdr, StandardCharsets.UTF_8).trim();
        String[] partes = linhaHdr.split(";");
        if (partes.length != 3) {
            throw new IllegalStateException("Ficheiro .hdr mal formado (esperado keyVersion;salt;nonce).");
        }
        int versao = Integer.parseInt(partes[0]);
        byte[] salt = Base64.getDecoder().decode(partes[1]);
        byte[] nonce = Base64.getDecoder().decode(partes[2]);

        // 1) o nome (chunkId) tem de ser o SHA-256 do conteudo cifrado
        String hashReal = ChunkCrypto.sha256Hex(ciphertext);
        if (!hashReal.equalsIgnoreCase(chunkId)) {
            throw new GeneralSecurityException("O SHA-256 do .bin nao corresponde ao chunkId (ficheiro alterado ou corrompido).");
        }
        // 2) versao da chave
        if (versao != ChunkCrypto.VERSAO_CHAVE) {
            throw new GeneralSecurityException("Versao de chave desconhecida: " + versao + " (esperada "
                    + ChunkCrypto.VERSAO_CHAVE + ").");
        }
        if (salt.length != ChunkCrypto.SALT_BYTES || nonce.length != ChunkCrypto.NONCE_BYTES) {
            throw new GeneralSecurityException("Salt ou nonce com tamanho inesperado no .hdr.");
        }
        // 3) autenticacao GCM (falha com GeneralSecurityException se algo nao bater certo)
        byte[] plaintext = ChunkCrypto.decifrar(chaveMestra, versao, salt, nonce, ciphertext);
        return new Sealed(chunkId, versao, salt, nonce, ciphertext, plaintext);
    }

    /** Reconstrui os registos do chunk (ignorando o padding). */
    public List<Registo> lerRegistos() {
        return Chunk.parse(plaintext);
    }

    public String getChunkId() {
        return chunkId;
    }

    /** Tamanho do .bin (texto cifrado com a tag, multiplo de PAD_BYTES). */
    public int getTamanhoFicheiro() {
        return ciphertext.length;
    }

    static Path ficheiroBin(Path pasta, String chunkId) {
        return pasta.resolve(chunkId + ".bin");
    }

    static Path ficheiroHdr(Path pasta, String chunkId) {
        return pasta.resolve(chunkId + ".hdr");
    }

    /**
     * O chunkId e sempre SHA-256 em hex: 64 caracteres [0-9a-f]. Isto tambem
     * impede que um id estranho (ex.: com ".." ou separadores de caminho) escape
     * da pasta selados/ atraves do nome do ficheiro.
     */
    private static void exigirChunkIdValido(String chunkId) {
        if (chunkId == null || !chunkId.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("chunkId invalido (esperado SHA-256 em hex): " + chunkId);
        }
    }
}
