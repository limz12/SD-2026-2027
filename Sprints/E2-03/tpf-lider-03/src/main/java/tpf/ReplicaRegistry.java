package tpf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ReplicaRegistry implements AutoCloseable {

    public enum EstadoReplica {
        VIVA,
        SUSPEITA,
        MORTA
    }

    public static final class SnapshotReplica {
        public final String id;
        public final EstadoReplica estado;
        public final long quotaBytes;
        public final long usadosBytes;
        public final long reservadosBytes;
        public final long ultimoHbMs;

        public SnapshotReplica(
                String id,
                EstadoReplica estado,
                long quotaBytes,
                long usadosBytes,
                long reservadosBytes,
                long ultimoHbMs) {
            this.id = id;
            this.estado = estado;
            this.quotaBytes = quotaBytes;
            this.usadosBytes = usadosBytes;
            this.reservadosBytes = reservadosBytes;
            this.ultimoHbMs = ultimoHbMs;
        }

        public long livreBytes() {
            return Math.max(0L, quotaBytes - usadosBytes - reservadosBytes);
        }
    }

    private final class HeartbeatMonitor {
        private final String idReplica;
        private final ScheduledFuture<?> tarefa;

        HeartbeatMonitor(String idReplica, long intervaloMs) {
            this.idReplica = idReplica;
            this.tarefa = scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            atualizarEstados(System.currentTimeMillis());
                        } catch (RuntimeException e) {
                            logger.accept("ERRO [HeartbeatMonitor:" + this.idReplica + "] " + e.getMessage());
                        }
                    },
                    intervaloMs,
                    intervaloMs,
                    TimeUnit.MILLISECONDS
            );
        }

        void cancelar() {
            tarefa.cancel(false);
        }
    }

    private static final class InfoReplica {
        final String id;
        EstadoReplica estado;
        long quotaBytes;
        long usadosBytes;
        long reservadosBytes;
        long ultimoHbMs;
        boolean removida;
        HeartbeatMonitor monitor;

        InfoReplica(String id, long usadosBytes, long quotaBytes, long agoraMs) {
            this.id = id;
            this.estado = EstadoReplica.VIVA;
            this.usadosBytes = usadosBytes;
            this.quotaBytes = quotaBytes;
            this.reservadosBytes = 0L;
            this.ultimoHbMs = agoraMs;
            this.removida = false;
        }

        SnapshotReplica toSnapshot() {
            return new SnapshotReplica(id, estado, quotaBytes, usadosBytes, reservadosBytes, ultimoHbMs);
        }
    }

    private final Object lock = new Object();
    private final Map<String, InfoReplica> replicas = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> logger;
    private volatile boolean fechado = false;

    public ReplicaRegistry() {
        this(System.out::println);
    }

    public ReplicaRegistry(Consumer<String> logger) {
        this.logger = (logger != null) ? logger : System.out::println;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replica-heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Regista a receção de um heartbeat HB;<id>;<usados>;<quota>.
     */
    public void onHeartbeat(String idReplica, long usados, long quota) {
        if (idReplica == null || idReplica.isBlank() || usados < 0L || quota <= 0L) {
            logger.accept("ERRO [ReplicaRegistry] Heartbeat inválido: id=" + idReplica
                    + " usados=" + usados + " quota=" + quota);
            return;
        }

        long agora = System.currentTimeMillis();
        long intervaloMonitor = Math.max(250L, Math.min(Wire.PERIODO_HB_MS, Wire.SUSPEITA_MS / 2L));

        synchronized (lock) {
            if (fechado) {
                return;
            }

            InfoReplica info = replicas.get(idReplica);
            if (info == null) {
                info = new InfoReplica(idReplica, usados, quota, agora);
                info.monitor = new HeartbeatMonitor(idReplica, intervaloMonitor);
                replicas.put(idReplica, info);
                logger.accept("ESTADO " + idReplica + " NOVA -> VIVA");
                return;
            }

            if (info.removida) {
                return;
            }

            EstadoReplica anterior = info.estado;
            info.usadosBytes = usados;
            info.quotaBytes = quota;
            info.ultimoHbMs = agora;
            info.estado = EstadoReplica.VIVA;

            if (info.monitor == null) {
                info.monitor = new HeartbeatMonitor(idReplica, intervaloMonitor);
            }

            if (anterior != EstadoReplica.VIVA) {
                logger.accept("ESTADO " + idReplica + " " + anterior + " -> VIVA");
            }
        }
    }

    /**
     * Avalia tempos desde o último HB e atualiza estados para SUSPEITA ou MORTA.
     */
    public void atualizarEstados(long agoraMs) {
        synchronized (lock) {
            for (InfoReplica info : replicas.values()) {
                if (info.removida) {
                    continue;
                }
                long decorrido = Math.max(0L, agoraMs - info.ultimoHbMs);
                EstadoReplica novoEstado;
                if (decorrido >= Wire.MORTA_MS) {
                    novoEstado = EstadoReplica.MORTA;
                } else if (decorrido >= Wire.SUSPEITA_MS) {
                    novoEstado = EstadoReplica.SUSPEITA;
                } else {
                    novoEstado = EstadoReplica.VIVA;
                }

                if (novoEstado != info.estado) {
                    EstadoReplica anterior = info.estado;
                    info.estado = novoEstado;
                    logger.accept("ESTADO " + info.id + " " + anterior + " -> " + novoEstado);
                }
            }
        }
    }

    /**
     * Escolhe atomicamente até {@code count} réplicas não-MORTA via Placement.choose
     * e reserva {@code tamanho} bytes em cada uma.
     */
    public List<String> reserveAndChoose(String chunkId, long tamanho, int count, Set<String> exclude) {
        Objects.requireNonNull(chunkId, "chunkId obrigatório");
        if (tamanho < 0L) {
            throw new IllegalArgumentException("tamanho deve ser >= 0");
        }
        if (count <= 0) {
            return Collections.emptyList();
        }

        long agora = System.currentTimeMillis();

        synchronized (lock) {
            atualizarEstados(agora);

            List<SnapshotReplica> candidatasNaoMortas = new ArrayList<>();
            for (InfoReplica info : replicas.values()) {
                if (!info.removida && info.estado != EstadoReplica.MORTA) {
                    candidatasNaoMortas.add(info.toSnapshot());
                }
            }

            List<String> escolhidas = Placement.choose(
                    chunkId,
                    tamanho,
                    count,
                    candidatasNaoMortas,
                    exclude
            );

            for (String idEscolhida : escolhidas) {
                InfoReplica info = replicas.get(idEscolhida);
                if (info != null) {
                    info.reservadosBytes += tamanho;
                }
            }

            return escolhidas;
        }
    }

    /**
     * Confirma a gravação de um chunk reservado: liberta a reserva e soma ao espaço usado estimado.
     */
    public void confirmarReserva(String idReplica, long tamanho) {
        if (idReplica == null || tamanho <= 0L) {
            return;
        }
        synchronized (lock) {
            InfoReplica info = replicas.get(idReplica);
            if (info == null) {
                return;
            }
            info.reservadosBytes = Math.max(0L, info.reservadosBytes - tamanho);
            info.usadosBytes += tamanho;
        }
    }

    /**
     * Liberta bytes previamente reservados sem confirmar gravação (timeout ou falha).
     */
    public void libertar(String idReplica, long tamanho) {
        if (idReplica == null || tamanho <= 0L) {
            return;
        }
        synchronized (lock) {
            InfoReplica info = replicas.get(idReplica);
            if (info == null) {
                return;
            }
            info.reservadosBytes = Math.max(0L, info.reservadosBytes - tamanho);
        }
    }

    /**
     * Exclui permanentemente a réplica da colocação e para o seu HeartbeatMonitor.
     */
    public boolean remover(String idReplica) {
        if (idReplica == null || idReplica.isBlank()) {
            return false;
        }
        synchronized (lock) {
            InfoReplica info = replicas.remove(idReplica);
            if (info == null) {
                return false;
            }
            EstadoReplica anterior = info.estado;
            info.removida = true;
            if (info.monitor != null) {
                info.monitor.cancelar();
                info.monitor = null;
            }
            logger.accept("ESTADO " + idReplica + " " + anterior + " -> REMOVIDA");
            return true;
        }
    }

    public List<SnapshotReplica> listarSnapshots() {
        synchronized (lock) {
            List<SnapshotReplica> lista = new ArrayList<>(replicas.size());
            for (InfoReplica info : replicas.values()) {
                if (!info.removida) {
                    lista.add(info.toSnapshot());
                }
            }
            return Collections.unmodifiableList(lista);
        }
    }

    public Map<String, EstadoReplica> estadosAtuais() {
        synchronized (lock) {
            Map<String, EstadoReplica> mapa = new HashMap<>();
            for (InfoReplica info : replicas.values()) {
                if (!info.removida) {
                    mapa.put(info.id, info.estado);
                }
            }
            return Collections.unmodifiableMap(mapa);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            fechado = true;
            for (InfoReplica info : replicas.values()) {
                if (info.monitor != null) {
                    info.monitor.cancelar();
                    info.monitor = null;
                }
            }
        }
        scheduler.shutdownNow();
    }
}
