
package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class Registo {

    private long lsn;
    private Instant instante;
    private String autor;
    private String canal;
    private String texto;

    public Registo(long lsn, Instant instante,
                   String autor, String canal, String texto) {

        this.lsn = lsn;
        this.instante = instante;
        this.autor = autor;
        this.canal = canal;
        this.texto = texto;
    }

    public long getLsn() {
        return lsn;
    }

    public Instant getInstante() {
        return instante;
    }

    public String getAutor() {
        return autor;
    }

    public String getCanal() {
        return canal;
    }

    public String getTexto() {
        return texto;
    }

    // Recuperar o último LSN existente no ficheiro
    public static long carregarUltimoLsn(Path caminhoLog)
            throws IOException {

        if (!Files.exists(caminhoLog)) {
            return 0;
        }

        long ultimoLsn = 0;

        for (String linha : Files.readAllLines(
                caminhoLog, StandardCharsets.UTF_8)) {

            if (linha.isBlank()) {
                continue;
            }

            String[] campos = linha.split(";", 5);

            if (campos.length != 5) {
                throw new IOException("Linha inválida em registos.log");
            }

            try {
                long lsn = Long.parseLong(campos[0]);

                if (lsn != ultimoLsn + 1) {
                    throw new IOException(
                        "Sequência de LSN inválida. Esperado: "
                        + (ultimoLsn + 1) + ", encontrado: " + lsn
                    );
                }

                ultimoLsn = lsn;

            } catch (NumberFormatException e) {
                throw new IOException("LSN inválido no ficheiro.", e);
            }
        }

        return ultimoLsn;
    }

     // Registar evento em eventos-lider.txt
    public static void registarEvento(
            Path caminhoEventos,
            String evento,
            String detalhe) throws IOException {

        // Criar o diretório caso ainda não exista
        if (caminhoEventos.getParent() != null) {
            Files.createDirectories(caminhoEventos.getParent());
        }

        String instante = Instant.now().toString();

        String linha = instante + ";"
            + evento + ";"
            + detalhe + "\n";

        ByteBuffer buffer = ByteBuffer.wrap(
            linha.getBytes(StandardCharsets.UTF_8)
        );

        try (FileChannel fileChannel = FileChannel.open(
                caminhoEventos,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {

            while (buffer.hasRemaining()) {
                fileChannel.write(buffer);
            }

            fileChannel.force(true);
        }
    }
}
