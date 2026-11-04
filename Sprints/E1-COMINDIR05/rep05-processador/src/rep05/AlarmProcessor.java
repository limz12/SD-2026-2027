package rep05;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Processador de alarmes (COMINDIR04): OBSERVA os LOTES de todas as partições,
 * cria dependências causais
 * entre partições (como na COMINDIR03) e, além disso, acompanha cada líder com
 * um HeartbeatMonitor.
 *
 * Uso: java [-Dsilencioso=true] [-Drep04.periodo=<ms>] [-Drep04.fator=<n>] -cp
 * out rep04.AlarmProcessor <N> [api]
 *
 * Carimbos: tudo como na COMINDIR03 (Lp e Vp = máximo de todos os registos
 * recebidos; alarmes e registos
 * ALARM- ignorados; duplicados ignorados). O que muda: a entrada são LOTES, e
 * por isso um alarme só é
 * produzido quando o lote da partição de origem CHEGA (até Δ depois de a
 * leitura ter sido registada) e só é
 * aplicado numa réplica quando o lote da partição de destino chegar (até mais
 * Δ). Os carimbos L e V do alarme
 * NÃO mudam: dependem do que o processador viu, não de quando o viu.
 *
 * Antes de publicar um alarme para a partição j: se o monitor de j estiver
 * SUSPEITO, avisa e publica na
 * mesma (sem retenção nem reenvio: o alarme pode perder-se sem deixar
 * vestígio).
 * Eventos do detetor: console + monitor-processador.txt
 * ("instante;evento;p<k>;detalhe").
 */
public class AlarmProcessor {
    static final boolean SILENCIOSO = Boolean.getBoolean("silencioso");

    static final Object LOCK = new Object();
    static long lp; // Lp: máximo dos L vistos (sob LOCK)
    static VectorClock vp; // Vp: máximo, componente a componente, dos V vistos (sob LOCK)
    static List<Set<Long>> vistos; // por partição, os seq já processados (deteção de duplicados)
    static HeartbeatMonitor[] monitores;

    // estatísticas desde o último resumo (sob LOCK)
    static int registos, alarmes, ignoradosAlarm, duplicados, rejeitados, falhas, avisos;
    static long ultimoMs;

    static void falar(String s) {
        if (!SILENCIOSO)
            System.out.println(s);
    }

    static synchronized void evento(String linha) {
        String l = System.currentTimeMillis() + ";" + linha;
        try {
            Files.writeString(Paths.get("monitor-processador.txt"), l + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("ERRO a escrever o ficheiro de eventos: " + e.getMessage());
        }
        if (!linha.startsWith("RECEBIDO"))
            System.out.println("[monitor] " + l);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: AlarmProcessor <N> [api]");
            return;
        }
        int n;
        Partitioner part;
        try {
            n = Integer.parseInt(args[0]);
            part = new Partitioner(n);
        } catch (IllegalArgumentException e) {
            System.out.println("N inválido: " + args[0]);
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub ps = new IpfsPubSub(api);

        vp = new VectorClock(n);
        lp = 0;
        vistos = new ArrayList<>();
        monitores = new HeartbeatMonitor[n];
        for (int k = 0; k < n; k++) {
            vistos.add(new HashSet<>());
            monitores[k] = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, AlarmProcessor::evento);
        }

        System.out.println("Processador de alarmes pronto (N = " + n + ", limiar = " + Wire.LIMIAR_ALARME
                + " °C, Δ=" + Wire.PERIODO_HB_MS + " ms, timeout=" + Wire.PERIODO_HB_MS * Wire.FATOR_TIMEOUT
                + " ms, API " + api + ").");

        for (int k = 0; k < n; k++) {
            final int kk = k;
            new Thread(() -> subscrever(kk, n, part, ps), "sub-p" + kk).start(); // uma thread por partição
        }

        Thread verificacao = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Wire.VERIFICACAO_MS);
                } catch (InterruptedException e) {
                    return;
                }
                for (HeartbeatMonitor m : monitores)
                    m.check();
            }
        }, "verificacao");
        verificacao.setDaemon(true);
        verificacao.start();

        Thread monitor = new Thread(AlarmProcessor::resumos, "resumos");
        monitor.setDaemon(true);
        monitor.start();
    }

    static void subscrever(int k, int n, Partitioner part, IpfsPubSub ps) {
        try {
            ps.subscribe(Wire.topicData(k), msg -> processarLote(k, n, part, ps, msg)); // BLOQUEIA: nunca termina
            System.out.println("[p" + k + "] subscrição terminada");
        } catch (IOException | InterruptedException e) {
            System.out.println("[p" + k + "] IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void processarLote(int k, int n, Partitioner part, IpfsPubSub ps, String msg) {
        Batch b;
        try {
            b = Batch.decode(msg, n); // dimensão do vetor dos registos tem de ser N
        } catch (IllegalArgumentException e) {
            synchronized (LOCK) {
                rejeitados++;
                ultimoMs = System.currentTimeMillis();
            }
            System.out.println("[p" + k + "] REJEITADO: " + msg.split("\n", 2)[0] + " (" + e.getMessage() + ")");
            return;
        }
        monitores[k].onHeartbeat(b.inc, b.hb); // mesmo de um lote de incarnação antiga: o monitor decide, os registos
                                               // valem
        String faixa = b.records.isEmpty() ? "-"
                : b.records.get(0).getSeq() + ".." + b.records.get(b.records.size() - 1).getSeq();
        evento("RECEBIDO;p" + k + ";inc=" + b.inc + ",hb=" + b.hb + ",ultimoSeq=" + b.lastSeq
                + ",n=" + b.records.size() + ",seq=" + faixa);
        for (SensorRecord r : b.records) {
            processarRegisto(k, part, ps, r);
        }
    }

    static void processarRegisto(int k, Partitioner part, IpfsPubSub ps, SensorRecord r) {
        // alarmes não geram alarmes (e, pela especificação, são ignorados: nem se
        // acumulam)
        if (r.getSensorId().startsWith("ALARM-")) {
            synchronized (LOCK) {
                ignoradosAlarm++;
            }
            return;
        }

        boolean alarme = r.getTemperature() >= Wire.LIMIAR_ALARME;
        long lSnap = 0;
        VectorClock vSnap = null;
        synchronized (LOCK) {
            if (!vistos.get(k).add(r.getSeq())) { // mesma partição e mesmo seq: duplicado
                duplicados++;
                return;
            }
            registos++;
            ultimoMs = System.currentTimeMillis();
            lp = Math.max(lp, r.getLamport()); // acumula o máximo de TODOS os registos...
            vp.merge(r.getVector()); // ... componente a componente
            if (alarme) {
                lSnap = lp;
                vSnap = vp.copy(); // instantâneo: o vetor partilhado continua a ser alterado pelas outras threads
            }
        }

        if (alarme) {
            String sensorAlarme = "ALARM-" + r.getSensorId();
            int j = part.of(sensorAlarme); // pode ser outra partição que a do registo que o originou
            if (monitores[j].state() == HeartbeatMonitor.State.SUSPEITO) {
                synchronized (LOCK) {
                    avisos++;
                }
                System.out.println("AVISO: líder p" + j + " suspeito; alarme " + sensorAlarme + " pode perder-se");
            }
            try {
                // fora do LOCK: o publish é um pedido HTTP lento e não deve bloquear as outras
                // threads
                ps.publish(Wire.topicIn(j), Wire.in(sensorAlarme, r.getTemperature(), lSnap, vSnap));
                synchronized (LOCK) {
                    alarmes++;
                }
                falar("ALARME: " + r.getSensorId() + " p" + k + "#" + r.getSeq() + " → p" + j
                        + " (L=" + lSnap + " V=" + vSnap + ")");
            } catch (IOException e) {
                synchronized (LOCK) {
                    falhas++;
                }
                System.out.println("FALHA A PUBLICAR ALARME: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 2 s sem registos novos depois de ter visto alguns = fim da rajada -> resumo
    static void resumos() {
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (LOCK) {
                if ((registos > 0 || rejeitados > 0) && System.currentTimeMillis() - ultimoMs >= 2000) {
                    System.out.println("--- RESUMO processador --- registos=" + registos + " | alarmes=" + alarmes
                            + " | ignorados(ALARM-)=" + ignoradosAlarm + " | duplicados=" + duplicados
                            + " | rejeitados=" + rejeitados + " | falhas=" + falhas + " | avisos(líder suspeito)="
                            + avisos
                            + " | Lp=" + lp + " | Vp=" + vp);
                    registos = alarmes = ignoradosAlarm = duplicados = rejeitados = falhas = avisos = 0;
                }
            }
        }
    }
}