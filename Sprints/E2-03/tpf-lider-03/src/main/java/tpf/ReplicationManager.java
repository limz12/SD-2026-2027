package tpf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ReplicationManager implements ReplicaMessageHandler.ListenerReplicacao, AutoCloseable {

    private static final class EstadoReplicacaoChunk {
        final String chunkId;
        final long tamanhoBin;
        final String hdrB64;
        final String binB64;
        final Set<String> tentadas = new LinkedHashSet<>();
        final Set<String> pendentesRonda = new LinkedHashSet<>();
        int rondaAtual = 0;
        ScheduledFuture<?> timeoutRonda;

        EstadoReplicacaoChunk(String chunkId, long tamanhoBin, String hdrB64, String binB64) {
            this.chunkId = chunkId;
            this.tamanhoBin = tamanhoBin;
            this.hdrB64 = hdrB64;
            this.binB64 = binB64;
        }
    }

    private final Object lock = new Object();
    private final Map<String, EstadoReplicacaoChunk> emCurso = new HashMap<>();

    private final ReplicaRegistry registry;
    private final Catalog catalog;
    private final PubSubHub hub;
    private final Path pastaSelados;
    private final Consumer<String> logger;
    private final ScheduledExecutorService scheduler;
    private volatile boolean fechado = false;

    public ReplicationManager(
            ReplicaRegistry registry,
            Catalog catalog,
            PubSubHub hub,
            Path pastaDados,
            Consumer<String> logger) {
        this.registry = Objects.requireNonNull(registry, "registry obrigatório");
        this.catalog = Objects.requireNonNull(catalog, "catalog obrigatório");
        this.hub = Objects.requireNonNull(hub, "hub obrigatório");
        this.pastaSelados = Objects.requireNonNull(pastaDados, "pastaDados obrigatória").resolve("selados");
        this.logger = (logger != null) ? logger : System.out::println;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replication-manager");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Ponto de engate chamado assim que um chunk é selado e gravado em selados/.
     */
    public void onChunkSelado(String chunkId, long lsnDe, long lsnAte) {
        if (fechado || chunkId == null || chunkId.isBlank()) {
            return;
        }

        try {
            Path pathHdr = pastaSelados.resolve(chunkId + ".hdr");
            Path pathBin = pastaSelados.resolve(chunkId + ".bin");

            byte[] hdrBytes = Files.readAllBytes(pathHdr);
            byte[] binBytes = Files.readAllBytes(pathBin);
            long tamanhoBin = binBytes.length;

            catalog.adicionar(chunkId, lsnDe, lsnAte, tamanhoBin);

            String hdrB64 = Base64.getEncoder().encodeToString(hdrBytes);
            String binB64 = Base64.getEncoder().encodeToString(binBytes);

            EstadoReplicacaoChunk estado = new EstadoReplicacaoChunk(chunkId, tamanhoBin, hdrB64, binB64);

            synchronized (lock) {
                if (fechado) {
                    return;
                }
                if (emCurso.containsKey(chunkId)) {
                    return;
                }
                emCurso.put(chunkId, estado);
                iniciarProximaRondaLocked(estado);
            }
        } catch (IOException e) {
            logger.accept("ERRO [ReplicationManager] Falha ao iniciar replicação de " + chunkId + ": " + e.getMessage());
        }
    }

    /**
     * Relança replicação de chunks do catálogo que ainda não atingiram Wire.R cópias
     * (por exemplo, após arranque de uma nova réplica ou recuperação do líder).
     */
    public void verificarChunksSubreplicados() {
        if (fechado) {
            return;
        }
        for (Catalog.EntradaCatalogo entrada : catalog.listarTodas()) {
            if (entrada.numeroCopias() >= Wire.R) {
                continue;
            }
            String chunkId = entrada.getChunkId();
            synchronized (lock) {
                if (emCurso.containsKey(chunkId)) {
                    continue;
                }
            }
            Path pathHdr = pastaSelados.resolve(chunkId + ".hdr");
            Path pathBin = pastaSelados.resolve(chunkId + ".bin");
            if (Files.isRegularFile(pathHdr) && Files.isRegularFile(pathBin)) {
                try {
                    byte[] hdrBytes = Files.readAllBytes(pathHdr);
                    byte[] binBytes = Files.readAllBytes(pathBin);
                    EstadoReplicacaoChunk estado = new EstadoReplicacaoChunk(
                            chunkId,
                            binBytes.length,
                            Base64.getEncoder().encodeToString(hdrBytes),
                            Base64.getEncoder().encodeToString(binBytes)
                    );
                    estado.tentadas.addAll(entrada.getReplicas());

                    synchronized (lock) {
                        if (!fechado && !emCurso.containsKey(chunkId)) {
                            emCurso.put(chunkId, estado);
                            iniciarProximaRondaLocked(estado);
                        }
                    }
                } catch (IOException e) {
                    logger.accept("ERRO [ReplicationManager] Falha ao relançar chunk " + chunkId + ": " + e.getMessage());
                }
            }
        }
    }

    private void iniciarProximaRondaLocked(EstadoReplicacaoChunk estado) {
        if (fechado) {
            return;
        }

        Set<String> jaConfirmadas = catalog.replicasConfirmadas(estado.chunkId);
        int emFalta = Wire.R - jaConfirmadas.size();
        if (emFalta <= 0) {
            emCurso.remove(estado.chunkId);
            return;
        }

        estado.rondaAtual++;
        if (estado.rondaAtual > Wire.MAX_RONDAS) {
            emCurso.remove(estado.chunkId);
            logger.accept("SUBREPLICADO " + estado.chunkId + " " + jaConfirmadas.size() + "/" + Wire.R
                    + " (esgotadas " + Wire.MAX_RONDAS + " rondas)");
            return;
        }

        logger.accept("RONDA " + estado.chunkId + " " + estado.rondaAtual);

        Set<String> excluir = new HashSet<>(estado.tentadas);
        excluir.addAll(jaConfirmadas);

        List<String> escolhidas = registry.reserveAndChoose(
                estado.chunkId,
                estado.tamanhoBin,
                emFalta,
                excluir
        );

        int totalPrevisto = jaConfirmadas.size() + escolhidas.size();
        if (totalPrevisto < Wire.R) {
            logger.accept("SUBREPLICADO " + estado.chunkId + " " + totalPrevisto + "/" + Wire.R);
        }

        if (escolhidas.isEmpty()) {
            emCurso.remove(estado.chunkId);
            return;
        }

        estado.pendentesRonda.clear();
        estado.pendentesRonda.addAll(escolhidas);
        estado.tentadas.addAll(escolhidas);

        String frameStore = "STORE;" + estado.chunkId + ";" + estado.hdrB64 + ";" + estado.binB64;
        for (String idReplica : escolhidas) {
            hub.publish("tpf-r-" + idReplica, frameStore);
        }

        final int rondaEsperada = estado.rondaAtual;
        estado.timeoutRonda = scheduler.schedule(
                () -> aoExpirarTimeoutRonda(estado.chunkId, rondaEsperada),
                Wire.ACK_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void aoExpirarTimeoutRonda(String chunkId, int rondaEsperada) {
        synchronized (lock) {
            if (fechado) {
                return;
            }
            EstadoReplicacaoChunk estado = emCurso.get(chunkId);
            if (estado == null || estado.rondaAtual != rondaEsperada) {
                return;
            }

            if (!estado.pendentesRonda.isEmpty()) {
                for (String idSemAck : estado.pendentesRonda) {
                    registry.libertar(idSemAck, estado.tamanhoBin);
                    logger.accept("ACK-TIMEOUT " + chunkId + " replica=" + idSemAck + " ronda=" + rondaEsperada);
                }
                estado.pendentesRonda.clear();
            }

            int copiasAtuais = catalog.copias(chunkId);
            if (copiasAtuais >= Wire.R) {
                emCurso.remove(chunkId);
                return;
            }

            iniciarProximaRondaLocked(estado);
        }
    }

    @Override
    public void aoReceberAck(String idReplica, String chunkId, String sha256) {
        if (!chunkId.equalsIgnoreCase(sha256)) {
            logger.accept("ACK-REJEITADO " + idReplica + " chunk=" + chunkId + " hash_divergente=" + sha256);
            return;
        }

        synchronized (lock) {
            EstadoReplicacaoChunk estado = emCurso.get(chunkId);
            if (estado == null || !estado.pendentesRonda.remove(idReplica)) {
                logger.accept("ACK-REJEITADO " + idReplica + " chunk=" + chunkId + " nao_pendente");
                return;
            }

            registry.confirmarReserva(idReplica, estado.tamanhoBin);
            try {
                catalog.confirmar(chunkId, idReplica);
            } catch (IOException e) {
                logger.accept("ERRO [ReplicationManager] Falha ao confirmar no catálogo: " + e.getMessage());
            }

            logger.accept("ACK " + chunkId + " replica=" + idReplica
                    + " copias=" + catalog.copias(chunkId) + "/" + Wire.R);

            if (estado.pendentesRonda.isEmpty()) {
                if (estado.timeoutRonda != null) {
                    estado.timeoutRonda.cancel(false);
                    estado.timeoutRonda = null;
                }

                if (catalog.copias(chunkId) >= Wire.R) {
                    emCurso.remove(chunkId);
                } else {
                    iniciarProximaRondaLocked(estado);
                }
            }
        }
    }

    @Override
    public void aoReceberHeartbeat(String idReplica) {
        verificarChunksSubreplicados();
    }

    @Override
    public void aoReceberInventario(String idReplica, List<String> chunkIds) {
        if (chunkIds != null) {
            for (String chunkId : chunkIds) {
                if (catalog.obter(chunkId) != null) {
                    try {
                        catalog.confirmar(chunkId, idReplica);
                    } catch (IOException e) {
                        logger.accept("ERRO [ReplicationManager] Falha ao reconciliar inventário de "
                                + idReplica + ": " + e.getMessage());
                    }
                }
            }
        }
        // Aproveita a entrada de uma réplica ativa para tentar completar eventuais chunks subreplicados
        verificarChunksSubreplicados();
    }

    @Override
    public void close() {
        synchronized (lock) {
            fechado = true;
            for (EstadoReplicacaoChunk estado : emCurso.values()) {
                if (estado.timeoutRonda != null) {
                    estado.timeoutRonda.cancel(false);
                }
                for (String pendente : estado.pendentesRonda) {
                    registry.libertar(pendente, estado.tamanhoBin);
                }
                estado.pendentesRonda.clear();
            }
            emCurso.clear();
        }
        scheduler.shutdownNow();
    }
}
