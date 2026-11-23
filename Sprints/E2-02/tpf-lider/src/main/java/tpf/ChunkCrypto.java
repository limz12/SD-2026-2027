package tpf;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class ChunkCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChunkCrypto() {}

    public static Sealed seal(byte[] plaintext, byte[] masterKey) throws Exception {
        requireMasterKey(masterKey);
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        return seal(plaintext, masterKey, salt, nonce, Wire.KEY_VERSION);
    }

    public static Sealed seal(byte[] plaintext, byte[] masterKey, byte[] salt,
                              byte[] nonce, int keyVersion) throws Exception {
        requireMasterKey(masterKey);
        if (salt == null || salt.length != 32) throw new IllegalArgumentException("salt deve ter 32 bytes");
        if (nonce == null || nonce.length != 12) throw new IllegalArgumentException("nonce deve ter 12 bytes");
        byte[] dek = deriveDek(masterKey, salt);
        byte[] ciphertext = gcm(dek, nonce, aad(keyVersion, salt, nonce), plaintext, Cipher.ENCRYPT_MODE);
        String id = sha256Hex(ciphertext);
        Arrays.fill(dek, (byte) 0);
        return new Sealed(id, ciphertext, salt, nonce, keyVersion);
    }

    public static byte[] open(Sealed sealed, byte[] masterKey) throws Exception {
        requireMasterKey(masterKey);
        if (sealed == null) throw new IllegalArgumentException("sealed é nulo");

        byte[] ciphertext = sealed.getCiphertext();
        String actualId = sha256Hex(ciphertext);
        if (!MessageDigest.isEqual(actualId.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                sealed.getChunkId().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new SecurityException("chunkId não corresponde ao conteúdo cifrado");
        }
        if (sealed.getKeyVersion() != Wire.KEY_VERSION) {
            throw new SecurityException("versão da chave desconhecida");
        }
        byte[] dek = deriveDek(masterKey, sealed.getSalt());
        try {
            return gcm(dek, sealed.getNonce(), aad(sealed.getKeyVersion(), sealed.getSalt(), sealed.getNonce()),
                    ciphertext, Cipher.DECRYPT_MODE);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new SecurityException("falha de autenticação GCM", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public static byte[] deriveDek(byte[] masterKey, byte[] salt) throws Exception {
        requireMasterKey(masterKey);
        if (salt == null || salt.length != 32) throw new IllegalArgumentException("salt deve ter 32 bytes");

        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = extract.doFinal(masterKey);

        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteBuffer input = ByteBuffer.allocate(Wire.INFO.length() + 1);
        input.put(Wire.INFO.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        input.put((byte) 0x01);
        byte[] okm = expand.doFinal(input.array());
        byte[] result = Arrays.copyOfRange(okm, 0, 32);
        Arrays.fill(prk, (byte) 0);
        Arrays.fill(okm, (byte) 0);
        return result;
    }

    public static String sha256Hex(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    public static byte[] aad(int keyVersion, byte[] salt, byte[] nonce) {
        ByteBuffer out = ByteBuffer.allocate(Wire.AAD_PREFIX.length + 4 + 32 + 12);
        out.put(Wire.AAD_PREFIX);
        out.putInt(keyVersion);
        out.put(salt);
        out.put(nonce);
        return out.array();
    }

    private static byte[] gcm(byte[] key, byte[] nonce, byte[] aad, byte[] input, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey secret = new SecretKeySpec(key, "AES");
        cipher.init(mode, secret, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static void requireMasterKey(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("master.key deve ter 32 bytes");
    }
}
