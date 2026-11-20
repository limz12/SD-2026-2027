package tpf;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.security.MessageDigest;
import java.security.SecureRandom;

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

public class ChunkCrypto {

    private static final int SALT_SIZE = 32;
    private static final int NONCE_SIZE = 12;
    private static final int DEK_SIZE = 32;
    private static final int TAG_SIZE = 128;

    private static final int KEY_VERSION = 1;

    private static final Path CAMINHO_CHAVE = Path.of(
        "./Sprints/E2-02/tpf-lider/src/main/java/tpf/chave-mestra/master.key"
    );

    private static final byte[] INFO =
            "tpf-chunk-v1".getBytes(StandardCharsets.UTF_8);

    private static final byte[] AAD_PREFIX =
            "tpf-v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] masterKey;

    // CONSTRUTOR: CARREGAR MASTER KEY
    public ChunkCrypto() throws IOException {

        if (!Files.exists(CAMINHO_CHAVE)) {
            throw new IOException(
                "Master Key não encontrada: " + CAMINHO_CHAVE.toAbsolutePath()
            );
        }

        byte[] chave = Files.readAllBytes(CAMINHO_CHAVE);

        if (chave.length != 32) {
            throw new IOException(
                "A Master Key deve ter exatamente 32 bytes."
            );
        }

        this.masterKey = chave.clone();

        Arrays.fill(chave, (byte) 0);
    }

    // SELAR CHUNK
    public Sealed seal(byte[] plaintext) throws Exception {

        byte[] salt = new byte[SALT_SIZE];
        RANDOM.nextBytes(salt);

        byte[] nonce = new byte[NONCE_SIZE];
        RANDOM.nextBytes(nonce);

        byte[] dek = hkdf(masterKey, salt);

        try {

            byte[] aad = construirAAD(salt, nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            SecretKeySpec chave = new SecretKeySpec(dek, "AES");

            GCMParameterSpec parametros =
                    new GCMParameterSpec(TAG_SIZE, nonce);

            cipher.init(Cipher.ENCRYPT_MODE, chave, parametros);

            cipher.updateAAD(aad);

            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] hash = MessageDigest
                    .getInstance("SHA-256")
                    .digest(ciphertext);

            String chunkId = HexFormat.of().formatHex(hash);

            return new Sealed(
                    chunkId,
                    KEY_VERSION,
                    salt,
                    nonce,
                    ciphertext
            );

        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    // ABRIR CHUNK
    public byte[] open(Sealed sealed, String expectedChunkId)
            throws Exception {

        // 1. Verificar hash
        byte[] hash = MessageDigest
                .getInstance("SHA-256")
                .digest(sealed.getCiphertext());

        String chunkIdCalculado =
                HexFormat.of().formatHex(hash);

        if (!MessageDigest.isEqual(
                chunkIdCalculado.getBytes(StandardCharsets.US_ASCII),
                expectedChunkId.getBytes(StandardCharsets.US_ASCII))) {

            throw new SecurityException("Hash do chunk inválido");
        }

        // 2. Verificar versão da chave
        if (sealed.getKeyVersion() != KEY_VERSION) {
            throw new SecurityException("Versão da chave inválida");
        }

        // 3. Derivar DEK
        byte[] dek = hkdf(masterKey, sealed.getSalt());

        try {

            byte[] aad = construirAAD(
                    sealed.getSalt(),
                    sealed.getNonce()
            );

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            SecretKeySpec chave = new SecretKeySpec(dek, "AES");

            GCMParameterSpec parametros =
                    new GCMParameterSpec(TAG_SIZE, sealed.getNonce());

            cipher.init(Cipher.DECRYPT_MODE, chave, parametros);

            cipher.updateAAD(aad);

            // Verifica a autenticação GCM
            return cipher.doFinal(sealed.getCiphertext());

        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    // HKDF-SHA256
    private byte[] hkdf(byte[] mk, byte[] salt)
            throws Exception {

        Mac mac = Mac.getInstance("HmacSHA256");

        // EXTRACT
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));

        byte[] prk = mac.doFinal(mk);

        try {

            // EXPAND: HMAC(PRK, info || 0x01)
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));

            byte[] input = Arrays.copyOf(INFO, INFO.length + 1);

            input[input.length - 1] = 0x01;

            byte[] okm = mac.doFinal(input);

            try {
                return Arrays.copyOf(okm, DEK_SIZE);
            } finally {
                Arrays.fill(okm, (byte) 0);
            }

        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    // CONSTRUIR AAD
    private byte[] construirAAD(byte[] salt, byte[] nonce) {

        ByteBuffer buffer = ByteBuffer.allocate(
                AAD_PREFIX.length + 4 + SALT_SIZE + NONCE_SIZE
        );

        buffer.put(AAD_PREFIX);
        buffer.putInt(KEY_VERSION);
        buffer.put(salt);
        buffer.put(nonce);

        return buffer.array();
    }

    // GERAR HEADER
    public String gerarHeader(Sealed sealed) {

        return sealed.getKeyVersion() + ";"
                + Base64.getEncoder().encodeToString(sealed.getSalt()) + ";"
                + Base64.getEncoder().encodeToString(sealed.getNonce()) + ";";
    }

    // LER HEADER
    public Sealed lerHeader(
            String chunkId,
            String header,
            byte[] ciphertext) {

        String[] linhas = header.strip().split(";");

        if (linhas.length != 3) {
            throw new IllegalArgumentException("Header inválido");
        }

        int versao = Integer.parseInt(linhas[0]);

        byte[] salt = Base64.getDecoder().decode(linhas[1]);

        byte[] nonce = Base64.getDecoder().decode(linhas[2]);

        if (salt.length != SALT_SIZE || nonce.length != NONCE_SIZE) {
            throw new IllegalArgumentException("Salt ou nonce inválido");
        }

        return new Sealed(
                chunkId,
                versao,
                salt,
                nonce,
                ciphertext
        );
    }
}
