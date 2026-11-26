package tpf;

/** Chunk cifrado e respetivos parâmetros públicos. */
public final class Sealed {
    private final String id;
    private final int keyVersion;
    private final byte[] salt, nonce, ciphertext;

    public Sealed(String id, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext) {
        this.id = java.util.Objects.requireNonNull(id);
        this.keyVersion = keyVersion;
        this.salt = salt.clone();
        this.nonce = nonce.clone();
        this.ciphertext = ciphertext.clone();
    }
    public String id() { return id; }
    public int keyVersion() { return keyVersion; }
    public byte[] salt() { return salt.clone(); }
    public byte[] nonce() { return nonce.clone(); }
    public byte[] ciphertext() { return ciphertext.clone(); }

    public static Sealed read(java.nio.file.Path pasta, String id) throws java.io.IOException {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new java.io.IOException("Identificador inválido");
        String hdr = java.nio.file.Files.readString(pasta.resolve(id + ".hdr"), java.nio.charset.StandardCharsets.UTF_8).strip();
        String[] campos = hdr.split(";", -1);
        if (campos.length != 3) throw new java.io.IOException("Cabeçalho inválido");
        try {
            return new Sealed(id, Integer.parseInt(campos[0]), java.util.Base64.getDecoder().decode(campos[1]),
                    java.util.Base64.getDecoder().decode(campos[2]), java.nio.file.Files.readAllBytes(pasta.resolve(id + ".bin")));
        } catch (IllegalArgumentException e) { throw new java.io.IOException("Cabeçalho inválido", e); }
    }
}
