package tpf;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ChunkCryptoTest {
    private byte[] masterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void cifraDecifra() throws Exception {
        byte[] mk = masterKey();
        byte[] plaintext = "teste tpf".getBytes();
        Sealed sealed = ChunkCrypto.seal(plaintext, mk);
        assertArrayEquals(plaintext, ChunkCrypto.open(sealed, mk));
    }

    @Test
    void alteraByteCifrado() throws Exception {
        byte[] mk = masterKey();
        Sealed sealed = ChunkCrypto.seal("conteudo".getBytes(), mk);
        byte[] ciphertext = sealed.getCiphertext();
        ciphertext[0] ^= 1;
        assertThrows(SecurityException.class,
                () -> ChunkCrypto.open(new Sealed(sealed.getChunkId(), ciphertext,
                        sealed.getSalt(), sealed.getNonce(), sealed.getKeyVersion()), mk));
    }

    @Test
    void alteraSaltNonceOuVersao() throws Exception {
        byte[] mk = masterKey();
        Sealed sealed = ChunkCrypto.seal("conteudo".getBytes(), mk);

        byte[] salt = sealed.getSalt();
        salt[0] ^= 1;
        assertThrows(Exception.class, () -> ChunkCrypto.open(
                new Sealed(sealed.getChunkId(), sealed.getCiphertext(), salt,
                        sealed.getNonce(), sealed.getKeyVersion()), mk));

        byte[] nonce = sealed.getNonce();
        nonce[0] ^= 1;
        assertThrows(Exception.class, () -> ChunkCrypto.open(
                new Sealed(sealed.getChunkId(), sealed.getCiphertext(), sealed.getSalt(),
                        nonce, sealed.getKeyVersion()), mk));

        assertThrows(SecurityException.class, () -> ChunkCrypto.open(
                new Sealed(sealed.getChunkId(), sealed.getCiphertext(), sealed.getSalt(),
                        sealed.getNonce(), 2), mk));
    }

    @Test
    void outraMasterKeyFalha() throws Exception {
        byte[] mk = masterKey();
        Sealed sealed = ChunkCrypto.seal("conteudo".getBytes(), mk);
        byte[] outra = masterKey();
        assertFalse(Arrays.equals(mk, outra));
        assertThrows(SecurityException.class, () -> ChunkCrypto.open(sealed, outra));
    }
}
