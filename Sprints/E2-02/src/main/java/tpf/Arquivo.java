package tpf;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * O caminho de escrita do lider, num so lugar.
 *
 * /registar, mensagens em #arquivo e /rajada passam TODOS por
 * {@link #registar(String, String, String)}, sincronizado:
 *
 *   1) o registo entra no diario (registos.log) com fsync,
 *   2) o evento REGISTO entra em eventos-lider.txt,
 *   3) o registo entra no chunk aberto; se o fizer exceder
 *      Wire.CHUNK_MAX_BYTES, o chunk selar-se de imediato (e abre um novo),
 *   4) o chunk aberto selar-se sozinho Wire.SELAGEM_MS apos o seu primeiro
 *      registo (ScheduledExecutorService).
 *
 * A selagem serializa o chunk com padding, cifra com AES-GCM (DEK derivada da
 * master.key com HKDF), grava selados/<chunkId>.bin + .hdr e registao evento
 * SELADO com o chunkId, o intervalo de LSN e o tamanho.
 */
public final class Arquivo implements Closeable {

    private final RegistoDuravel diario;
    private final Eventos eventos;
    private final Path pastaSelados;
    private final byte[] chaveMestra;
    private final ScheduledExecutorService agendador =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "selador-chunks");
                t.setDaemon(true);
                return t;
            });

    private Chunk aberto = new Chunk();
    private ScheduledFuture<?> selagemAgendada;

    public Arquivo(Path pastaRaiz, byte[] chaveMestra) throws IOException {
        this(pastaRaiz.resolve("registos.log"),
                pastaRaiz.resolve("eventos-lider.txt"),
                pastaRaiz.resolve("selados"),
                chaveMestra);
    }

    public Arquivo(Path caminhoDiario, Path caminhoEventos, Path pastaSelados, byte[] chaveMestra) throws IOException {
        this.diario = new RegistoDuravel(caminhoDiario);
        this.eventos = new Eventos(caminhoEventos);
        this.pastaSelados = pastaSelados;
        this.chaveMestra = chaveMestra.clone();
    }

    /** O ultimo LSN gravado no diario (0 se vazio). */
    public long getUltimoLsn() {
        return diario.getUltimoLsn();
    }

    /**
     * O metodo sincronizado por onde passam /registar, as mensagens de #arquivo
     * e a /rajada. Devolve o registo ja com o LSN atribuido, gravado com fsync.
     */
    public synchronized Registo registar(String autor, String canal, String texto) throws IOException {
        // 1) diario duravel com fsync
        Registo r = diario.acrescentar(autor, canal, texto);

        // 2) evento REGISTO
        eventos.registar("REGISTO", "lsn=" + r.lsn() + ";autor=" + r.autor());

        // 3) chunk aberto; o registo que excede o limite sela o chunk de imediato
        if (!aberto.cabe(r)) {
            selarInterno();
        }
        boolean eraVazio = aberto.vazio();
        aberto.adicionar(r);
        if (eraVazio && !aberto.isSelado()) {
            // 4) selagem por tempo: SELAGEM_MS apos o primeiro registo do chunk
            selagemAgendada = agendador.schedule(this::selarPorTempo, Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
        }
        return r;
    }

    /** Sela o chunk aberto agora (ex.: no fim do processo, ou nos testes). */
    public synchronized void selar() {
        selarInterno();
    }

    private void selarPorTempo() {
        synchronized (this) {
            selarInterno();
        }
    }

    /** So chamar com o lock de "this". */
    private void selarInterno() {
        if (selagemAgendada != null) {
            selagemAgendada.cancel(false);
            selagemAgendada = null;
        }
        if (aberto.vazio()) {
            aberto = new Chunk();
            return;
        }
        long lsnDe = aberto.primeiroLsn();
        long lsnAte = aberto.ultimoLsn();
        aberto.selar();
        try {
            Sealed selado = Sealed.cifrar(chaveMestra, aberto.serializar());
            selado.gravar(pastaSelados);
            eventos.registar("SELADO", "chunkId=" + selado.getChunkId()
                    + ";lsnDe=" + lsnDe + ";lsnAte=" + lsnAte
                    + ";bytes=" + selado.getTamanhoFicheiro());
        } catch (IOException | GeneralSecurityException e) {
            // Falha grave: o diario tem o registo, mas o chunk nao foi selado.
            System.err.println("[selar] FALHA ao selar chunk: " + e.getMessage());
            aberto = new Chunk(); // nao bloquear o lider para sempre
            return;
        }
        aberto = new Chunk();
    }

    @Override
    public synchronized void close() throws IOException {
        agendador.shutdownNow();
        // Best effort: selar o que ficou aberto para nada ficar pendente.
        selarInterno();
        diario.close();
    }
}
