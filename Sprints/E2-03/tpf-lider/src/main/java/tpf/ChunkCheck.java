package tpf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Verifica e abre os chunks locais sem apresentar o conteúdo. */
public final class ChunkCheck {
    private ChunkCheck() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Utilização: ChunkCheck <pasta de dados absoluta>");
        Path dados = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(dados.resolve("master.key"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("master.key inexistente ou inválida; não será criada pelo verificador");
        byte[] key = MasterKey.carregarOuCriar(dados.resolve("master.key")).getBytes();
        ChunkCrypto crypto;
        try { crypto = new ChunkCrypto(key); } finally { Arrays.fill(key, (byte) 0); }
        try (var ficheiros = Files.list(dados.resolve("selados"))) {
            for (Path bin : ficheiros.filter(p -> p.getFileName().toString().endsWith(".bin")).toList()) {
                String nome = bin.getFileName().toString();
                String id = nome.substring(0, nome.length() - 4);
                var registos = Chunk.abrir(crypto.open(Sealed.read(dados.resolve("selados"), id)));
                System.out.println(id + " LSN=" + registos.get(0).getLsn() + ".." + registos.get(registos.size() - 1).getLsn() + " registos=" + registos.size());
            }
        }
    }
}
