package raft04;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class PartitionNode {
    static int k, i, N = 3;
    static RaftState state;
    static long ultimoSeq;
    static long ultimoTermo;
    static long hb = 0;
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
        try {
            if (!Wire.type(msg).equals("IN")) { log("REJEITADO;" + msg.trim()); return; }
            String corpo = Wire.body(msg);
            String[] f = corpo.split(";", -1);
            if (f.length != 4) { log("REJEITADO;" + msg.trim()); return; }
            String sensor = f[0].trim();
            double temp = Double.parseDouble(f[1].trim());
            long lamportRecebido = Long.parseLong(f[2].trim());
            VectorClock vectorRecebido = VectorClock.parse(f[3].trim(), N);
            int destino = new Partitioner(N).of(sensor);
            if (destino != k) { log("PARTICAO_ERRADA;" + sensor); return; }
            synchronized (state) {
                long t = state.termIfLeader();
                if (t < 0) { log("IGNORADO;p" + k); return; }
                // atualizar relogios
                lamport = Math.max(lamport, lamportRecebido) + 1;
                VectorClock novoVector = vector.copy();
                novoVector.merge(vectorRecebido);
                novoVector.tick(k);
                long novoSeq = ultimoSeq + 1;
                if (novoVector.get(k) != novoSeq) { log("ERRO;V[" + k + "]=" + novoVector.get(k) + " != seq=" + novoSeq); return; }
                SensorRecord r = SensorRecord.now(novoSeq, t, sensor, temp, lamport, novoVector);
                // persistir registo
                try (FileWriter fw = new FileWriter("no-p" + k + "-n" + i + ".txt", true)) {
                    fw.write(r.toLine() + "\n");
                }
                ultimoSeq = novoSeq;
                ultimoTermo = t;
                lamport = lamport;
                vector = novoVector;
                pendentes.add(r);
                log("EVENTO;seq=" + ultimoSeq + ";termo=" + t);
            }
        } catch (Exception e) { log("ERRO;" + e.getMessage()); }
    }

    static void recuperarAte(long ate) {
        int lider = (int) state.leader();
        if (lider <= 0) lider = 1;
        String hostLider = "127.0.0.1";
        int porto = Wire.nodePort(k, lider);
        // tentar portos dos nós 1..3 se o líder falhar
        int[] cands = new int[]{lider, 1, 2, 3};
        for (int cand : cands) {
            int portoTent = Wire.nodePort(k, cand);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(hostLider, portoTent), Wire.T_TCP_MS);
                s.setSoTimeout(Wire.T_TCP_MS);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out.println("PEDIDO " + (ultimoSeq + 1) + " " + ate);
                List<String> novos = new ArrayList<>();
                String linha;
                while ((linha = in.readLine()) != null) {
                    if (linha.trim().equals("FIM")) break;
                    novos.add(linha.trim());
                }
                // append recuperados ao ficheiro
                try (FileWriter fw = new FileWriter("no-p" + k + "-n" + i + ".txt", true)) {
                    for (String l : novos) fw.write(l + "\n");
                }
                if (!novos.isEmpty()) {
                    String[] parts = novos.get(novos.size() - 1).split(";");
                    ultimoSeq = Long.parseLong(parts[0]);
                    ultimoTermo = Long.parseLong(parts[1]);
                }
                log("RECUPERACAO;de=" + (ultimoSeq + 1 - novos.size()) + ";ate=" + ate + ";n=" + novos.size());
                return;
            } catch (Exception ignored) {}
        }
        log("ERRO_RECUPERACAO;sem_lider");
    }

    static void onLote(String msg) {
        try {
            String[] lines = msg.trim().split("\n");
            String[] h = lines[0].trim().split(";");
            if (h.length < 9 || !h[0].equals("LOTE")) return;
            long bTerm = Long.parseLong(h[1]);
            int bLeader = Integer.parseInt(h[2]);
            long lastSeq = Long.parseLong(h[6]);
            long lastTerm = Long.parseLong(h[7]);
            int n = Integer.parseInt(h[8]);
            if (!state.onBatch(bTerm, bLeader)) { log("IGNORADO;p" + k + ";termo=" + bTerm); return; }
            log("RECEBIDO;p" + k + ";termo=" + bTerm + ";lider=n" + bLeader + ";ultimoSeq=" + lastSeq + ";ultimoTermo=" + lastTerm + ";n=" + n);
            // aplicar registos
            for (int idx = 1; idx <= n; idx++) {
                SensorRecord r = SensorRecord.fromLine(lines[idx], N);
                long seq = r.getSeq();
                long termo = r.getTerm();
                if (seq <= ultimoSeq) {
                    // duplicado ou divergência
                    if (termo == ultimoTermo) {
                        log("DUPLICADO;p" + k + ";seq=" + seq);
                    } else {
                        log("DIVERGENCIA;p" + k + ";seq=" + seq + ";termoLocal=" + ultimoTermo + ";termoRemoto=" + termo);
                    }
                    continue;
                }
                if (seq == ultimoSeq + 1) {
                    // append
                    try (FileWriter fw = new FileWriter("no-p" + k + "-n" + i + ".txt", true)) { fw.write(r.toLine() + "\n"); }
                    ultimoSeq = seq;
                    ultimoTermo = termo;
                } else {
                    // lacuna
                    log("LACUNA;p" + k + ";falta=" + (ultimoSeq + 1));
                    recuperarAte(seq);
                }
            }
            // verificar divergência final
            if (ultimoSeq < lastSeq || (ultimoSeq == lastSeq && ultimoTermo != lastTerm)) {
                log("DIVERGENCIA_FINAL;p" + k + ";local=" + ultimoSeq + "/" + ultimoTermo + ";remoto=" + lastSeq + "/" + lastTerm);
                recuperarAte(lastSeq);
            }
            if (ultimoSeq > lastSeq) {
                log("TRUNCAR;possivel;p" + k);
            }
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
        List<SensorRecord> lote;
        long seqLote;
        long termoLote;
        long hbLote;
        synchronized (state) {
            lote = pendentes;
            pendentes = new ArrayList<>();
            seqLote = ultimoSeq;
            termoLote = ultimoTermo;
            // incrementar hb: guardar em campo estático
            hb++;
            hbLote = hb;
        }
        Batch b = new Batch(t, i, host, Wire.nodePort(k, i), hbLote, seqLote, termoLote, lote);
        String msg = b.encode();
        try {
            ps.publish(Wire.topicData(k), msg);
            long bytes = msg.getBytes(StandardCharsets.UTF_8).length;
            try (FileWriter fw = new FileWriter("hb-p" + k + "-n" + i + ".txt", true)) {
                fw.write(t + ";" + hbLote + ";" + System.currentTimeMillis() + ";" + seqLote + ";" + termoLote + ";" + lote.size() + ";" + bytes + "\n");
            }
            log("HEARTBEAT;hb=" + hbLote + ";n=" + lote.size() + ";ultimoSeq=" + seqLote);
        } catch (Exception e) { log("ERRO;" + e.getMessage()); }
    }

    static void atender(Socket s) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            s.setSoTimeout(Wire.T_TCP_MS);
            String pedido = in.readLine();
            if (pedido == null) return;
            String[] p = pedido.trim().split("\\s+");
            if (p.length != 3 || !p[0].equals("PEDIDO")) { out.println("ERRO pedido mal formado"); return; }
            long de = Long.parseLong(p[1]);
            long ate = Long.parseLong(p[2]);
            if (de <= 0 || ate <= 0 || de > ate) { out.println("ERRO intervalo invalido"); return; }
            File f = new File("no-p" + k + "-n" + i + ".txt");
            List<String> lines = Files.exists(f.toPath()) ? Files.readAllLines(f.toPath()) : List.of();
            for (String l : lines) {
                String[] parts = l.split(";");
                long seq = Long.parseLong(parts[0]);
                if (seq >= de && seq <= ate) out.println(l);
            }
            out.println("FIM");
        } catch (Exception e) {
            log("ERRO;" + e.getMessage());
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
