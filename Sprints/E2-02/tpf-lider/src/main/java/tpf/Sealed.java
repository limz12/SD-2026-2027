package tpf;

public final class Sealed {

    private final String chunkId;
    private final int keyVersion;
    private final byte[] salt;
    private final byte[] nonce;
    private final byte[] ciphertext;

    public Sealed(
            String chunkId,
            int keyVersion,
            byte[] salt,
            byte[] nonce,
            byte[] ciphertext) {

        this.chunkId = chunkId;
        this.keyVersion = keyVersion;
        this.salt = salt.clone();
        this.nonce = nonce.clone();
        this.ciphertext = ciphertext.clone();
    }

    public String getChunkId() {
        return chunkId;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public byte[] getSalt() {
        return salt.clone();
    }

    public byte[] getNonce() {
        return nonce.clone();
    }

    public byte[] getCiphertext() {
        return ciphertext.clone();
    }
}
