
package tpf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Gestão de registos.log e dos LSNs.
 *
 * O ficheiro é mantido aberto e bloqueado
 * exclusivamente durante a vida da instância.
 *
 * Recuperação:
 * - usa o mesmo FileChannel bloqueado;
 * - valida todas as linhas;
 * - exige LSNs 1, 2, 3, ...;
 * - exige LF no final de cada linha;
 * - não modifica ficheiros inválidos.
 *
 * Escrita:
 * - synchronized;
 * - um LSN por operação;
 * - escrita completa;
 * - force(true) antes de confirmar;
 * - estado FALHA perante escrita incerta.
 *
 * Esta classe não garante integridade
 * criptográfica nem coordena chunks.
 */
public final class RegistoLog implements AutoCloseable {

    public enum Estado {
        ATIVO,
        FALHA,
        FECHADO
    }

    private final Path caminho;
    private final FileChannel canal;
    private final FileLock bloqueio;

    private long ultimoLsn;
    private Estado estado;

    public RegistoLog(Path caminho) throws IOException {

        if (caminho == null) {
            throw new IllegalArgumentException(
                "O caminho de registos.log é obrigatório."
            );
        }

        this.caminho =
                caminho.toAbsolutePath().normalize();

        Path pasta = this.caminho.getParent();

        if (pasta == null || !Files.isDirectory(pasta)) {
            throw new IOException(
                "A pasta de registos.log não existe: "
                + pasta
            );
        }

        if (Files.exists(
                this.caminho,
                LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(
                    this.caminho,
                    LinkOption.NOFOLLOW_LINKS)) {

            throw new IOException(
                "registos.log não é um ficheiro regular: "
                + this.caminho
            );
        }

        FileChannel aberto = null;
        FileLock lock = null;

        try {

            aberto = FileChannel.open(
                this.caminho,
                Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                )
            );

            try {

                lock = aberto.tryLock();

            } catch (OverlappingFileLockException e) {

                throw new IOException(
                    "registos.log já está bloqueado "
                    + "nesta JVM.",
                    e
                );
            }

            if (lock == null) {
                throw new IOException(
                    "registos.log está bloqueado "
                    + "por outro processo."
                );
            }

            /*
             * Recuperar usando o próprio canal,
             * sem abrir outro FileChannel ou stream.
             */
            long recuperado = recuperarUltimoLsn(aberto, null);

            aberto.position(aberto.size());

            this.canal = aberto;
            this.bloqueio = lock;
            this.ultimoLsn = recuperado;
            this.estado = Estado.ATIVO;

        } catch (IOException | RuntimeException | Error e) {

            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException limpeza) {
                    e.addSuppressed(limpeza);
                }
            }

            if (aberto != null) {
                try {
                    aberto.close();
                } catch (IOException limpeza) {
                    e.addSuppressed(limpeza);
                }
            }

            throw e;
        }
    }

    /**
     * Recupera o último LSN a partir do canal
     * que já possui o bloqueio exclusivo.
     *
     * As leituras são posicionais:
     * read(buffer, posicao).
     *
     * Assim, a posição de escrita do canal
     * não é alterada durante a recuperação.
     */
    private static long recuperarUltimoLsn(
            FileChannel canal, java.util.List<Registo> registos) throws IOException {

        long tamanho = canal.size();

        if (tamanho == 0) {
            return 0;
        }

        /*
         * Política conservadora:
         * a última linha tem de terminar com LF.
         *
         * A ausência de LF não demonstra
         * necessariamente que os campos estejam
         * incompletos; apenas viola o formato
         * de escrita escolhido.
         */
        ByteBuffer finalByte = ByteBuffer.allocate(1);

        int lidos = canal.read(
            finalByte,
            tamanho - 1
        );

        if (lidos != 1) {
            throw new IOException(
                "Não foi possível ler o último byte "
                + "de registos.log."
            );
        }

        finalByte.flip();

        if (finalByte.get() != (byte) '\n') {
            throw new IOException(
                "registos.log não termina com o "
                + "terminador de linha LF obrigatório. "
                + "O ficheiro foi preservado."
            );
        }

        long ultimo = 0;
        long numeroLinha = 0;

        /*
         * Ler por blocos, sem criar outro canal.
         */
        ByteBuffer bloco = ByteBuffer.allocate(8192);
        ByteArrayOutputStream linha =
                new ByteArrayOutputStream();

        long posicao = 0;

        while (posicao < tamanho) {

            bloco.clear();

            int quantidade = (int) Math.min(
                bloco.capacity(),
                tamanho - posicao
            );

            bloco.limit(quantidade);

            int n = canal.read(bloco, posicao);

            if (n < 0) {
                throw new IOException(
                    "Fim inesperado durante a "
                    + "recuperação de registos.log."
                );
            }

            if (n == 0) {
                throw new IOException(
                    "A recuperação não conseguiu "
                    + "avançar na leitura do log."
                );
            }

            posicao += n;
            bloco.flip();

            while (bloco.hasRemaining()) {

                byte b = bloco.get();

                if (b == (byte) '\n') {

                    numeroLinha++;

                    String textoLinha = descodificarLinha(
                        linha.toByteArray(),
                        numeroLinha
                    );

                    Registo registo;

                    try {

                        registo = Registo.fromLine(
                            textoLinha
                        );

                    } catch (IllegalArgumentException e) {

                        throw new IOException(
                            "Registo inválido na linha "
                            + numeroLinha
                            + ": " + e.getMessage(),
                            e
                        );
                    }

                    if (ultimo == Long.MAX_VALUE) {
                        throw new IOException(
                            "Limite máximo de LSN atingido."
                        );
                    }

                    long esperado = ultimo + 1;

                    if (registo.getLsn() != esperado) {

                        throw new IOException(
                            "LSN fora de sequência na linha "
                            + numeroLinha
                            + ": esperado "
                            + esperado
                            + ", encontrado "
                            + registo.getLsn()
                        );
                    }

                    ultimo = registo.getLsn();
                    if (registos != null) registos.add(registo);
                    linha.reset();

                } else {

                    linha.write(b);
                }
            }
        }

        if (linha.size() != 0) {

            throw new IOException(
                "Foram encontrados bytes após "
                + "a última linha terminada."
            );
        }

        return ultimo;
    }

    /**
     * Descodifica uma linha usando UTF-8 estrito.
     *
     * Rejeita bytes inválidos e linhas vazias.
     */
    private static String descodificarLinha(
            byte[] bytes,
            long numeroLinha) throws IOException {

        if (bytes.length == 0) {
            throw new IOException(
                "Linha vazia em registos.log: "
                + numeroLinha
            );
        }

        /*
         * O formato de escrita usa LF.
         * CRLF não é produzido por esta classe.
         */
        if (bytes[bytes.length - 1] == '\r') {
            throw new IOException(
                "Terminador CRLF não previsto "
                + "na linha " + numeroLinha
                + ". Esperado LF."
            );
        }

        try {

            CharBuffer chars = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )
                .decode(ByteBuffer.wrap(bytes));

            return chars.toString();

        } catch (CharacterCodingException e) {

            throw new IOException(
                "UTF-8 inválido na linha "
                + numeroLinha,
                e
            );
        }
    }

    /**
     * Atribui o próximo LSN, escreve a linha
     * e confirma a escrita em disco.
     *
     * Uma exceção antes de iniciar a escrita,
     * causada por dados de entrada inválidos,
     * não coloca o log em FALHA.
     *
     * Qualquer falha após o início da operação
     * de escrita coloca o log em FALHA.
     */
    public synchronized Registo registar(
            String autor,
            String canalOrigem,
            String texto) throws IOException {

        verificarAtivo();

        if (ultimoLsn == Long.MAX_VALUE) {
            throw new IllegalStateException(
                "Não existem mais LSNs disponíveis."
            );
        }

        long proximo = ultimoLsn + 1;

        /*
         * Criar e validar antes de escrever.
         */
        Registo registo = new Registo(
            proximo,
            System.currentTimeMillis(),
            autor,
            canalOrigem,
            texto
        );

        byte[] bytes = (
            registo.toLine() + "\n"
        ).getBytes(StandardCharsets.UTF_8);

        try {

            /*
             * A operação de escrita começa aqui.
             * Em caso de falha, não reutilizar LSN.
             */
            canal.position(canal.size());

            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {

                int escritos = canal.write(buffer);

                if (escritos <= 0) {
                    throw new IOException(
                        "Escrita sem progresso."
                    );
                }
            }

            canal.force(true);

            ultimoLsn = proximo;

            return registo;

        } catch (IOException | RuntimeException e) {

            estado = Estado.FALHA;

            throw new IOException(
                "Falha ao confirmar o LSN "
                + proximo
                + ". registos.log passou a FALHA "
                + "e não aceita novas escritas.",
                e
            );
        }
    }

    private void verificarAtivo() {

        if (estado == Estado.FALHA) {
            throw new IllegalStateException(
                "registos.log encontra-se em FALHA. "
                + "É necessário interromper o líder "
                + "e verificar o ficheiro."
            );
        }

        if (estado == Estado.FECHADO) {
            throw new IllegalStateException(
                "registos.log já foi fechado."
            );
        }
    }

    public synchronized long getUltimoLsn() {
        return ultimoLsn;
    }

    /** Lê e valida novamente o log usando o canal e bloqueio já abertos. */
    public synchronized java.util.List<Registo> lerTodos() throws IOException {
        verificarAtivo();
        java.util.List<Registo> resultado = new java.util.ArrayList<>();
        long ultimo = recuperarUltimoLsn(canal, resultado);
        if (ultimo != ultimoLsn) throw new IOException("Log alterado durante a recuperação");
        return java.util.List.copyOf(resultado);
    }

    public synchronized Estado getEstado() {
        return estado;
    }

    public Path getCaminho() {
        return caminho;
    }

    /**
     * Fecha o log e liberta o bloqueio.
     *
     * Uma falha na libertação do bloqueio
     * não impede a tentativa de fechar o canal.
     */
    @Override
    public synchronized void close() throws IOException {

        if (estado == Estado.FECHADO) {
            return;
        }

        estado = Estado.FECHADO;

        IOException erro = null;

        try {

            if (bloqueio.isValid()) {
                bloqueio.release();
            }

        } catch (IOException e) {

            erro = e;
        }

        try {

            canal.close();

        } catch (IOException e) {

            if (erro == null) {
                erro = e;
            } else {
                erro.addSuppressed(e);
            }
        }

        if (erro != null) {
            throw erro;
        }
    }
}
