package tpf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ArquivoLocalRecoveryTest {
    @TempDir Path dados;
    private final byte[] chave = new byte[32];

    @Test void selaRegistosQueEstavamApenasNoLogEReinicioEIdempotente() throws Exception {
        Path caminhoLog = dados.resolve("registos.log");
        try (RegistoLog log = new RegistoLog(caminhoLog)) {
            log.registar("123", "456", "primeiro");
            log.registar("123", "456", "segundo");
        }
        try (RegistoLog log = new RegistoLog(caminhoLog);
             ArquivoLocal arquivo = new ArquivoLocal(log, chave, dados)) {
            assertEquals(2, log.getUltimoLsn());
            assertEquals(1, contarBins());
        }
        try (RegistoLog log = new RegistoLog(caminhoLog);
             ArquivoLocal arquivo = new ArquivoLocal(log, chave, dados)) {
            assertEquals(2, log.getUltimoLsn());
            assertEquals(1, contarBins());
            assertEquals(2, log.lerTodos().size());
        }
    }

    @Test void concluiPublicacaoParcialVerificaEDesfazPendencia() throws Exception {
        Path caminhoLog = dados.resolve("registos.log");
        Registo r;
        try (RegistoLog log = new RegistoLog(caminhoLog)) { r = log.registar("123", "456", "teste"); }
        Chunk chunk = new Chunk(); chunk.adicionar(r);
        Sealed s = new ChunkCrypto(chave).seal(chunk.selar(Wire.PAD_BYTES));
        Path pasta = Files.createDirectories(dados.resolve("selados"));
        String hdr = "1;" + Base64.getEncoder().encodeToString(s.salt()) + ";" + Base64.getEncoder().encodeToString(s.nonce()) + "\n";
        Files.write(pasta.resolve(s.id() + ".bin"), s.ciphertext());
        Files.writeString(pasta.resolve(s.id() + ".hdr.tmp"), hdr, StandardCharsets.UTF_8);
        Files.createFile(pasta.resolve(s.id() + ".pending"));
        try (RegistoLog log = new RegistoLog(caminhoLog);
             ArquivoLocal arquivo = new ArquivoLocal(log, chave, dados)) {
            assertTrue(Files.exists(pasta.resolve(s.id() + ".hdr")));
            assertFalse(Files.exists(pasta.resolve(s.id() + ".pending")));
            assertEquals(1, contarBins());
        }
    }

    @Test void naoAceitaParIncompletoSemMarcador() throws Exception {
        Path pasta = Files.createDirectories(dados.resolve("selados"));
        Path orfao = pasta.resolve("a".repeat(64) + ".bin");
        Files.write(orfao, new byte[]{1});
        try (RegistoLog log = new RegistoLog(dados.resolve("registos.log"))) {
            assertThrows(java.io.IOException.class, () -> new ArquivoLocal(log, chave, dados));
            assertTrue(Files.exists(orfao));
        }
    }
    private long contarBins() throws Exception {
        try (var paths = Files.list(dados.resolve("selados"))) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".bin")).count();
        }
    }
}
