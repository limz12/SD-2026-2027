package tpf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * O diario de eventos do lider: eventos-lider.txt, com linhas
 *
 *   instante;evento;detalhe
 *
 * Ex.:  1730000000123;REGISTO;lsn=7;autor=ana
 *       1730000050456;SELADO;chunkId=ab12...;lsnDe=1;lsnAte=7;bytes=4096
 */
public final class Eventos {

    private final Path caminho;

    public Eventos(Path caminho) {
        this.caminho = caminho;
    }

    /** Acrescenta um evento. Sincronizado para as linhas nao se misturarem. */
    public synchronized void registar(String evento, String detalhe) {
        String linha = System.currentTimeMillis() + ";" + evento + ";" + detalhe + "\n";
        try {
            Files.writeString(caminho, linha, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // O diario de eventos e auxiliar: nao pode travar o registo duravel,
            // mas o aviso fica na consola.
            System.err.println("[eventos] nao consegui escrever em " + caminho + ": " + e.getMessage());
        }
    }
}
