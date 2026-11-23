package tpf;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra de chunks: HKDF-SHA256 para derivar a DEK da chave mestra e AES-256-GCM
 * para cifrar o texto com padding.
 *
 * Derivacao da DEK (HKDF, RFC 5869):
 *   PRK = HMAC-SHA256(salt, MK)            (extract)
 *   DEK = HMAC-SHA256(PRK, info || 0x01)   (expand, um unico bloco, 32 bytes)
 *   com info = "tpf-chunk-v1"
 *
 * Cifra:
 *   AES-256-GCM, tag de 128 bits
 *   aad = "tpf-v1" || keyVersion (4 bytes big-endian) || salt (32) || nonce (12)
 *   keyVersion = 1
 *
 * Identificacao:
 *   chunkId = SHA-256 em hexadecimal do TEXTO CIFRADO, incluindo a tag
 */
public final class ChunkCrypto {

    public static final String INFO_HKDF = "tpf-chunk-v1";
    public static final String AAD_PREFIXO = "tpf-v1";
    public static final int VERSAO_CHAVE = 1;

    public static final int SALT_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int DEK_BYTES = 32;
    public static final int TAG_BYTES = 16;   // tag GCM de 128 bits
    public static final int TAG_BITS = 128;

    private static final String ALGORITMO_MAC = "HmacSHA256";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private ChunkCrypto() {
    }

    /** Resultado de cifrar um chunk: tudo o que e preciso para gravar .bin e .hdr. */
    public record ChunkCifrado(String chunkId, byte[] ciphertext, int keyVersion,
                               byte[] salt, byte[] nonce) {
    }

    /** Cifra o plaintext com salt e nonce novos, gerados aleatoriamente. */
    public static ChunkCifrado cifrar(byte[] chaveMestra, byte[] plaintext) throws GeneralSecurityException {
        byte[] salt = novoAleatorio(SALT_BYTES);
        byte[] nonce = novoAleatorio(NONCE_BYTES);
        byte[] ciphertext = cifrarCom(chaveMestra, VERSAO_CHAVE, salt, nonce, plaintext);
        return new ChunkCifrado(sha256Hex(ciphertext), ciphertext, VERSAO_CHAVE, salt, nonce);
    }

    /**
     * Decifra. A autenticacao GCM (tag incluida) e verificada pelo AES-GCM:
     * qualquer alteracao ao ciphertext, aad, salt, nonce ou chave faz falhar
     * com GeneralSecurityException.
     */
    public static byte[] decifrar(byte[] chaveMestra, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek(chaveMestra, salt), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(keyVersion, salt, nonce));
        return cipher.doFinal(ciphertext);
    }

    /** Deriva a DEK da chave mestra e o salt com HKDF-SHA256 (extract + 1 bloco de expand). */
    static byte[] dek(byte[] chaveMestra, byte[] salt) throws GeneralSecurityException {
        return hkdfSha256(chaveMestra, salt, INFO_HKDF.getBytes(java.nio.charset.StandardCharsets.UTF_8), DEK_BYTES);
    }

    /** HKDF-SHA256 generica (RFC 5869), para os vetores de teste oficiais. */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int tamanho) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(ALGORITMO_MAC);

        // Extract: PRK = HMAC-SHA256(salt, IKM)
        mac.init(new SecretKeySpec(salt, ALGORITMO_MAC));
        byte[] prk = mac.doFinal(ikm);

        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01) — um unico bloco basta
        // para 32 bytes, porque o bloco do SHA-256 e de 32 bytes.
        mac.init(new SecretKeySpec(prk, ALGORITMO_MAC));
        mac.update(info);
        mac.update((byte) 0x01);
        byte[] bloco = mac.doFinal();
        if (tamanho > bloco.length) {
            throw new IllegalStateException("HKDF: " + tamanho + " bytes pediria mais do que um bloco (nao e o caso do TPF).");
        }
        return java.util.Arrays.copyOf(bloco, tamanho);
    }

    private static byte[] cifrarCom(byte[] chaveMestra, int keyVersion, byte[] salt, byte[] nonce, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek(chaveMestra, salt), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(keyVersion, salt, nonce));
        return cipher.doFinal(plaintext);
    }

    private static byte[] aad(int keyVersion, byte[] salt, byte[] nonce) {
        byte[] prefixo = AAD_PREFIXO.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] aad = new byte[prefixo.length + 4 + salt.length + nonce.length];
        System.arraycopy(prefixo, 0, aad, 0, prefixo.length);
        int pos = prefixo.length;
        for (int i = 0; i < 4; i++) {
            aad[pos + i] = (byte) (keyVersion >>> (8 * (3 - i)));
        }
        pos += 4;
        System.arraycopy(salt, 0, aad, pos, salt.length);
        System.arraycopy(nonce, 0, aad, pos + salt.length, nonce.length);
        return aad;
    }

    /** SHA-256 em hexadecimal (minusculas) de uns bytes quaisquer. */
    public static String sha256Hex(byte[] dados) throws GeneralSecurityException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(dados);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static byte[] novoAleatorio(int bytes) {
        byte[] b = new byte[bytes];
        ALEATORIO.nextBytes(b);
        return b;
    }

    /** Para os testes e para o .hdr: salt/nonce em Base64. */
    static String base64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
