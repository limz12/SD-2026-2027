package tpf;

public final class Sealed {
    private final String chunkId;
    private final byte[] ciphertext;
    private final byte[] salt;
    private final byte[] nonce;
    private final int keyVersion;

    public Sealed(String chunkId, byte[] ciphertext, byte[] salt, byte[] nonce, int keyVersion) {
        this.chunkId = chunkId;
        this.ciphertext = ciphertext.clone();
        this.salt = salt.clone();
        this.nonce = nonce.clone();
        this.keyVersion = keyVersion;
    }

    public String getChunkId() { return chunkId; }
    public String id() { return chunkId; }
    public byte[] getCiphertext() { return ciphertext.clone(); }
    public byte[] ciphertext() { return getCiphertext(); }
    public byte[] getSalt() { return salt.clone(); }
    public byte[] salt() { return getSalt(); }
    public byte[] getNonce() { return nonce.clone(); }
    public byte[] nonce() { return getNonce(); }
    public int getKeyVersion() { return keyVersion; }
    public int keyVersion() { return keyVersion; }
}
