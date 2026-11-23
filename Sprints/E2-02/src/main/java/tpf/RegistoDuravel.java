package tpf;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * O registo duravel: append-only em registos.log com fsync antes de responder.
 *
 * Cada escrita:
 *   1) atribui o LSN seguinte (no arranque, a numeracao continua a partir do
 *      ultimo LSN valido em registos.log),
 *   2) acrescenta a linha "lsn;instante;autor;canal;textoBase64",
 *   3) forca os dados e os metadados para o disco com FileChannel.force(true),
 *   4) e so entao o chamador responde com o LSN atribuido.
 *
 * A linha final pode ficar incompleta se o processo morrer a meio da escrita:
 * no arranque, uma linha mal formada e ignorada (so acontece no fim, porque as
 * escritas sao sincronizadas e com fsync).
 */
public final class RegistoDuravel implements Closeable {

    private final Path caminho;
    private final FileChannel ficheiro; // canal do registos.log
    private long ultimoLsn;

    public RegistoDuravel(Path caminho) throws IOException {
        this.caminho = caminho;
        Path pai = caminho.toAbsolutePath().getParent();
        if (pai != null) {
            Files.createDirectories(pai);
        }
        this.ultimoLsn = lerUltimoLsn(caminho);
        this.ficheiro = FileChannel.open(caminho,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /**
     * Acrescenta um registo ao diario e devolve-o ja com LSN e instante.
     * Sincronizado: as varias fontes (/registar, mensagens em #arquivo, /rajada)
     * passam todas por aqui, pelo que os LSN ficam sem repeticoes nem saltos.
     */
    public synchronized Registo acrescentar(String autor, String canal, String texto) throws IOException {
        Registo r = new Registo(ultimoLsn + 1, System.currentTimeMillis(),
                Registo.limpar(autor), Registo.limpar(canal), texto);
        byte[] linha = (r.linhaParaDiario() + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(linha);
        while (buffer.hasRemaining()) {
            ficheiro.write(buffer);
        }
        ficheiro.force(true); // fsync dos dados E dos metadados: so depois disto ha resposta
        ultimoLsn = r.lsn();
        return r;
    }

    /** O ultimo LSN gravado (0 se o diario estava vazio). */
    public synchronized long getUltimoLsn() {
        return ultimoLsn;
    }

    @Override
    public synchronized void close() throws IOException {
        ficheiro.force(true);
        ficheiro.close();
    }

    /**
     * No arranque: le o diario existente e devolve o ultimo LSN valido, para a
     * numeracao continuar a partir dele. Linhas mal formadas sao ignoradas.
     */
    static long lerUltimoLsn(Path caminho) throws IOException {
        if (!Files.isRegularFile(caminho)) {
            return 0L;
        }
        String conteudo = Files.readString(caminho, StandardCharsets.UTF_8);
        long ultimo = 0L;
        for (String linha : conteudo.split("\n", -1)) {
            Registo r = Registo.daLinhaDiario(linha.trim());
            if (r != null) {
                ultimo = Math.max(ultimo, r.lsn());
            }
        }
        return ultimo;
    }
}
