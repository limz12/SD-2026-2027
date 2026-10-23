package rep03;

import java.io.IOException;
import java.util.*;

/**
 * Processador de alarmes: OBSERVA todas as partições e ESCREVE noutra, criando dependências causais
 * entre partições (o registo do alarme depende de tudo o que o processador viu antes de o publicar).
 *
 * Uso: java [-Dsilencioso=true] -cp out rep03.AlarmProcessor <N> [api]
 *
 * Estado partilhado: Lp (Lamport) e Vp (vetor), atualizados pelas N threads de subscrição (uma por
 * partição) e protegidos por LOCK. O processador NÃO gera eventos (não faz tick): só acumula o MÁXIMO de
 * tudo o que viu, ou seja, o "conhecimento causal" que transporta nas mensagens IN. Acumula o máximo de
 * TODOS os registos recebidos e não só do que origina o alarme: assim o alarme depende causalmente de
 * tudo o que o processador já observou (um limite superior, seguro). Registar só o do registo-causa
 * perderia relações (por exemplo, o alarme não ficaria depois de um registo de outra partição observado
 * um instante antes).
 */
public class AlarmProcessor {
    static final boolean SILENCIOSO = Boolean.getBoolean("silencioso");

    static final Object LOCK = new Object();
    static long lp;                       // Lp: máximo dos L vistos (sob LOCK)
    static VectorClock vp;                // Vp: máximo, componente a componente, dos V vistos (sob LOCK)
    static List<Set<Long>> vistos;        // por partição, os seq já processados (deteção de duplicados)

    // estatísticas desde o último resumo (sob LOCK)
    static int registos, alarmes, ignoradosAlarm, duplicados, rejeitados, falhas;
    static long ultimoMs;

    static void falar(String s) {
        if (!SILENCIOSO) System.out.println(s);
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
        for (int k = 0; k < n; k++) vistos.add(new HashSet<>());

        System.out.println("Processador de alarmes pronto (N = " + n + ", limiar = " + Wire.LIMIAR_ALARME
                + " °C, API " + api + ").");

        for (int k = 0; k < n; k++) {
            final int kk = k;
            new Thread(() -> subscrever(kk, n, part, ps), "sub-p" + kk).start(); // uma thread por partição
        }

        Thread monitor = new Thread(AlarmProcessor::monitor, "monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    static void subscrever(int k, int n, Partitioner part, IpfsPubSub ps) {
        try {
            ps.subscribe(Wire.topicData(k), msg -> processar(k, n, part, ps, msg)); // BLOQUEIA: nunca termina
            System.out.println("[p" + k + "] subscrição terminada");
        } catch (IOException | InterruptedException e) {
            System.out.println("[p" + k + "] IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void processar(int k, int n, Partitioner part, IpfsPubSub ps, String msg) {
        SensorRecord r;
        try {
            r = SensorRecord.fromLine(Wire.regParts(msg)[2], n); // dimensão do vetor tem de ser N
        } catch (IllegalArgumentException e) {
            synchronized (LOCK) { rejeitados++; ultimoMs = System.currentTimeMillis(); }
            System.out.println("[p" + k + "] REJEITADO: " + msg + " (" + e.getMessage() + ")");
            return;
        }

        // alarmes não geram alarmes (e, pela especificação, são ignorados: nem se acumulam)
        if (r.getSensorId().startsWith("ALARM-")) {
            synchronized (LOCK) { ignoradosAlarm++; }
            return;
        }

        boolean alarme = r.getTemperature() >= Wire.LIMIAR_ALARME;
        long lSnap = 0;
        VectorClock vSnap = null;
        synchronized (LOCK) {
            if (!vistos.get(k).add(r.getSeq())) {      // mesma partição e mesmo seq: duplicado
                duplicados++;
                return;
            }
            registos++;
            ultimoMs = System.currentTimeMillis();
            lp = Math.max(lp, r.getLamport());          // acumula o máximo de TODOS os registos...
            vp.merge(r.getVector());                    // ... componente a componente
            if (alarme) {
                lSnap = lp;
                vSnap = vp.copy();  // instantâneo: o vetor partilhado continua a ser alterado pelas outras threads
            }
        }

        if (alarme) {
            String sensorAlarme = "ALARM-" + r.getSensorId();
            int j = part.of(sensorAlarme); // pode ser outra partição que a do registo que o originou
            try {
                // fora do LOCK: o publish é um pedido HTTP lento e não deve bloquear as outras threads
                ps.publish(Wire.topicIn(j), Wire.in(sensorAlarme, r.getTemperature(), lSnap, vSnap));
                synchronized (LOCK) { alarmes++; }
                falar("ALARME: " + r.getSensorId() + " p" + k + "#" + r.getSeq() + " → p" + j
                        + " (L=" + lSnap + " V=" + vSnap + ")");
            } catch (IOException e) {
                synchronized (LOCK) { falhas++; }
                System.out.println("FALHA A PUBLICAR ALARME: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 2 s sem registos depois de ter visto alguns = fim da rajada -> resumo
    static void monitor() {
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
                            + " | rejeitados=" + rejeitados + " | falhas=" + falhas
                            + " | Lp=" + lp + " | Vp=" + vp);
                    registos = alarmes = ignoradosAlarm = duplicados = rejeitados = falhas = 0;
                }
            }
        }
    }
}