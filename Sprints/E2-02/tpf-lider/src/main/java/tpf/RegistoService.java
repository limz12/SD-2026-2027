
package tpf;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordena a execução assíncrona dos pedidos
 * de registo provenientes do Discord.
 *
 * Existe uma única instância por líder.
 *
 * Ambos os listeners utilizam o mesmo serviço
 * e, consequentemente, a mesma RegistoLog.
 *
 * A execução é sequencial:
 * - uma thread de escrita;
 * - uma fila FIFO limitada;
 * - um registo de cada vez;
 * - sem bloqueio das threads de eventos JDA.
 *
 * A durabilidade é garantida por RegistoLog,
 * que executa FileChannel.force(true).
 *
 * Este serviço não implementa chunks,
 * eventos REGISTO ou cifra.
 */
public final class RegistoService implements AutoCloseable {

    private static final int CAPACIDADE_FILA = 1000;

    private final RegistoLog registoLog;
    private final ArquivoLocal arquivo;

    private final ThreadPoolExecutor executor;

    private final AtomicBoolean encerramento =
            new AtomicBoolean(false);

    /**
     * Recebe a instância única de RegistoLog.
     */
    public RegistoService(RegistoLog registoLog, MasterKey masterKey, java.nio.file.Path dados) throws IOException {

        this.registoLog = Objects.requireNonNull(
            registoLog,
            "RegistoLog é obrigatória."
        );
        byte[] chave = masterKey.getBytes();
        try { this.arquivo = new ArquivoLocal(registoLog, chave, dados); }
        finally { java.util.Arrays.fill(chave, (byte) 0); }

        /*
         * Uma única thread preserva a ordem
         * de execução dos pedidos aceites.
         *
         * A fila é limitada para evitar
         * crescimento ilimitado de memória.
         */
        this.executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CAPACIDADE_FILA),
            tarefa -> {

                Thread thread = new Thread(
                    tarefa,
                    "tpf-registo-writer"
                );

                thread.setDaemon(false);

                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Submete um registo para escrita durável.
     *
     * O método não executa operações de disco
     * na thread que o invoca.
     *
     * O CompletableFuture só termina com
     * sucesso após RegistoLog.registar()
     * devolver, isto é, após force(true).
     *
     * Um erro é propagado ao CompletableFuture.
     *
     * Não existe repetição automática.
     */
    public CompletableFuture<Registo> registar(
            String autorId,
            String canalId,
            String texto) {

        CompletableFuture<Registo> resultado =
                new CompletableFuture<>();

        if (encerramento.get()) {

            resultado.completeExceptionally(
                new IllegalStateException(
                    "O serviço de registo está "
                    + "em encerramento."
                )
            );

            return resultado;
        }

        try {

            executor.execute(() -> {

                try {

                    Registo registo =
                            arquivo.registar(
                                autorId,
                                canalId,
                                texto
                            );

                    /*
                     * Apenas completar com sucesso
                     * após a escrita durável.
                     */
                    resultado.complete(registo);

                } catch (Exception e) {

                    resultado.completeExceptionally(e);
                }
            });

        } catch (RejectedExecutionException e) {

            resultado.completeExceptionally(
                new IllegalStateException(
                    "A fila de registos está cheia "
                    + "ou o serviço está encerrado.",
                    e
                )
            );
        }

        return resultado;
    }

    /**
     * Disponibiliza o último LSN conhecido.
     */
    public long getUltimoLsn() {
        return registoLog.getUltimoLsn();
    }

    /**
     * Disponibiliza o estado da RegistoLog.
     */
    public RegistoLog.Estado getEstado() {
        return registoLog.getEstado();
    }

    /**
     * Inicia o encerramento.
     *
     * Deixa de aceitar pedidos e aguarda
     * pela conclusão dos pedidos já aceites.
     *
     * Não interrompe deliberadamente
     * uma operação force(true) em curso.
     */
    @Override
    public void close() throws IOException {

        encerramento.set(true);
        executor.shutdown();

        try {

            boolean terminou = executor.awaitTermination(
                30,
                TimeUnit.SECONDS
            );

            if (!terminou) {

                /*
                 * Não fechar a RegistoLog enquanto
                 * ainda existirem escritas em curso.
                 */
                throw new IOException(
                    "O serviço de registo não terminou "
                    + "as operações pendentes durante "
                    + "o encerramento."
                );
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IOException(
                "Encerramento do serviço de registo "
                + "interrompido.",
                e
            );
        }

        /*
         * Só fechar o ficheiro depois de
         * a thread de escrita terminar.
         */
        try { arquivo.close(); }
        finally { registoLog.close(); }
    }
}
