package tpf;

/** Snapshot dos bytes cifrados de um chunk já publicado localmente. */
public record SealedChunk(String id, long lsnDe, long lsnAte, byte[] header, byte[] bin) {
    public SealedChunk {
        header = header.clone();
        bin = bin.clone();
    }
    @Override public byte[] header() { return header.clone(); }
    @Override public byte[] bin() { return bin.clone(); }
    public long tamanho() { return bin.length; }
}
