package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** HKDF-SHA256 e AES-256-GCM do protocolo tpf-v1. */
public final class ChunkCrypto {
    private static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_PREFIX = "tpf-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] master;

    public ChunkCrypto(byte[] master) {
        if (master == null || master.length != 32) throw new IllegalArgumentException("master.key deve ter 32 bytes");
        this.master = master.clone();
    }
    private byte[] derive(byte[] salt) throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(master);
        try {
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            hmac.update(INFO);
            return hmac.doFinal(new byte[]{1});
        } finally { Arrays.fill(prk, (byte) 0); }
    }
    private byte[] aad(int version, byte[] salt, byte[] nonce) {
        return ByteBuffer.allocate(AAD_PREFIX.length + 4 + salt.length + nonce.length)
                .put(AAD_PREFIX).putInt(version).put(salt).put(nonce).array();
    }
    public static String hash(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public Sealed seal(byte[] padded) throws GeneralSecurityException {
        byte[] salt = new byte[32], nonce = new byte[12];
        RANDOM.nextBytes(salt); RANDOM.nextBytes(nonce);
        byte[] key = derive(salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(1, salt, nonce));
            byte[] ciphertext = cipher.doFinal(padded);
            return new Sealed(hash(ciphertext), 1, salt, nonce, ciphertext);
        } finally { Arrays.fill(key, (byte) 0); }
    }
    public byte[] open(Sealed sealed) throws GeneralSecurityException {
        byte[] ciphertext = sealed.ciphertext();
        if (!hash(ciphertext).equals(sealed.id())) throw new GeneralSecurityException("Hash do chunk inválido");
        if (sealed.keyVersion() != 1) throw new GeneralSecurityException("Versão de chave inválida");
        byte[] salt = sealed.salt(), nonce = sealed.nonce();
        if (salt.length != 32 || nonce.length != 12) throw new GeneralSecurityException("Cabeçalho inválido");
        byte[] key = derive(salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(sealed.keyVersion(), salt, nonce));
            return cipher.doFinal(ciphertext);
        } finally { Arrays.fill(key, (byte) 0); }
    }
}
