package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Processador de alarmes (COMINDIR05): OBSERVA os LOTES de todas as partições,
 * cria dependências causais entre partições (como na COMINDIR03) e acompanha o
 * líder ELEITO de cada partição com um HeartbeatMonitor (termos, não
 * incarnações).
 *
 * Uso: java [-Dsilencioso=true] [-Draft04.periodo=<ms>] [-Draft04.fator=<n>]
 *          -cp out raft04.AlarmProcessor <N> [api]
 *
 * - trata apenas lotes cujo termo seja >= ao maior termo já observado para
 *   essa partição (lotes de líderes obsoletos são IGNORADOS);
 * - o monitor assinala NOVO_LIDER quando o termo aumenta, HB_PERDIDO quando há
 *   salto de hb no mesmo termo, SUSPEITA/RETOMA como na ficha anterior;
 * - trata cada registo pelo PAR (seq, termo), ignorando pares já tratados: um
 *   registo com o mesmo seq e OUTRO termo é um registo DIFERENTE (pode gerar de
 *   novo o alarme, se a temperatura o justificar);
 * - antes de publicar um alarme para a partição j, se o monitor de j estiver
 *   SUSPEITO, assinala "AVISO: partição p<j> sem líder conhecido; alarme
 *   <sensor> pode perder-se" e publica-o na mesma (sem retenção nem reenvio).
 *
 * Eventos em monitor-processador.txt: "instante;evento;p<k>;detalhe".
 */
public class AlarmProcessor {
    static final boolean SILENCIOSO = Boolean.getBoolean("silencioso");

    static final Object LOCK = new Object();
    static long lp; // Lp: máximo dos L vistos (sob LOCK)
    static VectorClock vp; // Vp: máximo, componente a componente, dos V vistos (sob LOCK)
    static List<Set<String>> vistos; // por partição, os pares "seq;termo" já tratados
    static HeartbeatMonitor[] monitores;
    static long[] maxTermo; // maior termo de lote já tratado, por partição
    static long[] hbVisto; // último hb tratado nesse termo, por partição

    // estatísticas desde o último resumo (sob LOCK)
    static int registos, alarmes, ignoradosAlarm, duplicados, rejeitados, falhas, avisos, obsoletos;

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
        maxTermo = new long[n];
        hbVisto = new long[n];
        for (int k = 0; k < n; k++) {
            vistos.add(new HashSet<>());
            monitores[k] = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, AlarmProcessor::evento);
            maxTermo[k] = 0;
            hbVisto[k] = 0;
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
        synchronized (LOCK) {
            if (b.term < maxTermo[k]) {
                obsoletos++;
                evento("IGNORADO;p" + k + ";termo=" + b.term + " (obsoleto; máximo observado=" + maxTermo[k] + ")");
                return; // líder obsoleto: nem o monitor nem os registos o consideram
            }
            if (b.term == maxTermo[k] && b.hb <= hbVisto[k]) {
                return; // duplicado ou atrasado
            }
            maxTermo[k] = b.term;
            hbVisto[k] = b.hb;
        }
        monitores[k].onHeartbeat(b.term, b.leader, b.hb);
        String faixa = b.records.isEmpty() ? "-"
                : b.records.get(0).getSeq() + ".." + b.records.get(b.records.size() - 1).getSeq();
        evento("RECEBIDO;p" + k + ";termo=" + b.term + ",hb=" + b.hb + ",lider=n" + b.leader + ",ultimoSeq="
                + b.lastSeq + ",n=" + b.records.size() + ",seq=" + faixa);
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
            if (!vistos.get(k).add(r.getSeq() + ";" + r.getTerm())) {
                // mesmo par (seq, termo): duplicado. O mesmo seq com OUTRO termo é
                // um registo diferente e é tratado de novo.
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
                evento("AVISO;p" + j + ";" + sensorAlarme);
                System.out.println("AVISO: partição p" + j + " sem líder conhecido; alarme " + sensorAlarme
                        + " pode perder-se");
            }
            try {
                // fora do LOCK: o publish é um pedido HTTP lento e não deve bloquear as
                // outras threads
                ps.publish(Wire.topicIn(j), Wire.in(sensorAlarme, r.getTemperature(), lSnap, vSnap));
                synchronized (LOCK) {
                    alarmes++;
                }
                falar("ALARME: " + r.getSensorId() + " p" + k + "#" + r.getSeq() + "/" + r.getTerm() + " → p" + j
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
                            + " | obsoletos=" + obsoletos + " | rejeitados=" + rejeitados + " | falhas=" + falhas
                            + " | avisos(sem líder)=" + avisos + " | Lp=" + lp + " | Vp=" + vp);
                    registos = alarmes = ignoradosAlarm = duplicados = rejeitados = falhas = avisos = obsoletos = 0;
                }
            }
        }
    }
}
