package tpf;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Atualiza estados VIVA/SUSPEITA/MORTA segundo a idade dos heartbeats. */
public final class HeartbeatMonitor implements AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tpf-heartbeat-monitor");
        t.setDaemon(true);
        return t;
    });

    public HeartbeatMonitor(ReplicaRegistry registry) {
        scheduler.scheduleAtFixedRate(() -> registry.atualizarEstados(System.currentTimeMillis()),
                Wire.PERIODO_HB_MS, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
    }

    @Override public void close() { scheduler.shutdownNow(); }
}
