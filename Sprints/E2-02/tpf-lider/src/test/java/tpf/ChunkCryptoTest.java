package tpf;

import static org.junit.jupiter.api.Assertions.*;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ChunkCryptoTest {
    @Test void cifraEVerificaIntegridade() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        ChunkCrypto crypto = new ChunkCrypto(key);
        byte[] original = "registo de teste".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Sealed sealed = crypto.seal(original);
        assertArrayEquals(original, crypto.open(sealed));
        byte[] bin = sealed.ciphertext(); bin[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> crypto.open(new Sealed(sealed.id(), 1, sealed.salt(), sealed.nonce(), bin)));
        byte[] salt = sealed.salt(); salt[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> crypto.open(new Sealed(sealed.id(), 1, salt, sealed.nonce(), sealed.ciphertext())));
        byte[] nonce = sealed.nonce(); nonce[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> crypto.open(new Sealed(sealed.id(), 1, sealed.salt(), nonce, sealed.ciphertext())));
        assertThrows(GeneralSecurityException.class, () -> crypto.open(new Sealed(sealed.id(), 2, sealed.salt(), sealed.nonce(), sealed.ciphertext())));
        byte[] outra = key.clone(); outra[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> new ChunkCrypto(outra).open(sealed));
    }
}
