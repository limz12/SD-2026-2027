package raft04;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class PartitionNode {
    static int k, i, N = 3;
    static RaftState state;
    static long ultimoSeq;
    static long ultimoTermo;
    static long lamport;
    static VectorClock vector;
    static List<SensorRecord> pendentes = new ArrayList<>();
    static ScheduledExecutorService scheduler;
    static IpfsPubSub ps;
    static String host = "127.0.0.1";

    static void log(String msg) {
        String out = System.currentTimeMillis() + ";" + msg;
        System.out.println(out);
        try (FileWriter fw = new FileWriter("eventos-p" + k + "-n" + i + ".txt", true)) {
            fw.write(out + "\n");
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("Uso: PartitionNode <k> <i> [N]"); return; }
        k = Integer.parseInt(args[0]);
        i = Integer.parseInt(args[1]);
        if (args.length > 2) N = Integer.parseInt(args[2]);

        File f = new File("no-p" + k + "-n" + i + ".txt");
        if (f.exists()) {
            List<String> ls = Files.readAllLines(f.toPath());
            if (!ls.isEmpty()) {
                String[] parts = ls.get(ls.size()-1).split(";");
                ultimoSeq = Long.parseLong(parts[0]);
                ultimoTermo = Long.parseLong(parts[1]);
            }
        } else { ultimoSeq = 0; ultimoTermo = 0; }

        Path stateFile = new File("estado-p" + k + "-n" + i + ".txt").toPath();
        state = new RaftState(k, i, Wire.GRUPO, Wire.ELEICAO_MIN_MS, Wire.ELEICAO_MAX_MS, stateFile, PartitionNode::log);

        int porto = Wire.nodePort(k, i);
        ServerSocket ss = new ServerSocket(porto);
        new Thread(() -> { while (true) { try { Socket s = ss.accept(); atender(s); } catch (Exception ignored) {} } }, "tcp").start();

        String api = "http://127.0.0.1:5001/api/v0";
        ps = new IpfsPubSub(api);

        new Thread(() -> { try { ps.subscribe(Wire.topicIn(k), msg -> onIn(msg)); } catch (Exception e) {} }, "in").start();
        new Thread(() -> { try { ps.subscribe(Wire.topicData(k), msg -> onLote(msg)); } catch (Exception e) {} }, "data").start();
        new Thread(() -> { try { ps.subscribe(Wire.topicElection(k), msg -> onEleicao(msg)); } catch (Exception e) {} }, "elec").start();

        new Thread(() -> {
            while (true) {
                long t = state.tick();
                if (t >= 0) {
                    try {
                        ps.publish(Wire.topicElection(k), new VoteRequest(t, i, ultimoSeq, ultimoTermo).encode());
                    } catch (Exception ignored) {}
                }
                try { Thread.sleep(Wire.VERIFICACAO_MS); } catch (InterruptedException e) { break; }
            }
        }, "check").start();
    }

    static void onIn(String msg) {
        // simplificado: apenas log
        log("IN_RECEBIDO;" + msg.trim());
    }

    static void onLote(String msg) {
        try {
            String[] lines = msg.trim().split("\n");
            String[] h = lines[0].trim().split(";");
            if (h.length < 9 || !h[0].equals("LOTE")) return;
            long bTerm = Long.parseLong(h[1]);
            int bLeader = Integer.parseInt(h[2]);
            if (!state.onBatch(bTerm, bLeader)) { log("IGNORADO;p" + k + ";termo=" + bTerm); return; }
            log("RECEBIDO;p" + k + ";termo=" + bTerm + ";lider=n" + bLeader);
        } catch (Exception e) { log("ERRO;" + e.getMessage()); }
    }

    static void onEleicao(String msg) {
        try {
            if (msg.startsWith("PEDIDO_VOTO")) {
                VoteRequest vr = VoteRequest.decode(msg);
                RaftState.Vote v = state.onVoteRequest(vr.term, vr.candidate, vr.lastSeq, vr.lastTerm, ultimoSeq, ultimoTermo);
                ps.publish(Wire.topicElection(k), new VoteReply(v.term, i, vr.candidate, v.granted).encode());
            } else if (msg.startsWith("VOTO")) {
                VoteReply vr = VoteReply.decode(msg);
                if (state.onVote(vr.term, vr.voter, vr.candidate, vr.granted)) {
                    // tornar-se lider
                    log("LIDER;p" + k + ";termo=" + state.term());
                    scheduler = Executors.newSingleThreadScheduledExecutor();
                    scheduler.scheduleAtFixedRate(PartitionNode::enviarLote, 0, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) { log("ERRO;" + e.getMessage()); }
    }

    static void enviarLote() {
        long t = state.termIfLeader();
        if (t < 0) { if (scheduler != null) scheduler.shutdown(); return; }
        // simplificado: publicar lote vazio
        try {
            Batch b = new Batch(t, i, host, Wire.nodePort(k,i), 0, ultimoSeq, ultimoTermo, new ArrayList<>());
            ps.publish(Wire.topicData(k), b.encode());
            log("HEARTBEAT;hb=0;ultimoSeq=" + ultimoSeq);
        } catch (Exception e) { log("ERRO;" + e.getMessage()); }
    }

    static void atender(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
