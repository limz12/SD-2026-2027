package raft04;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

public class Producer {
    static HeartbeatMonitor[] monitors;
    static int n;

    static void logEvent(String line) {
        String out = System.currentTimeMillis() + ";" + line;
        System.out.println(out);
        try (FileWriter fw = new FileWriter("monitor-produtor.txt", true)) { fw.write(out + "\n"); } catch (IOException ignored) {}
    }

    static void logProdutor(String line) {
        try (FileWriter fw = new FileWriter("produtor.txt", true)) { fw.write(System.currentTimeMillis() + ";" + line + "\n"); } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.out.println("Uso: Producer <N> [api]"); return; }
        n = Integer.parseInt(args[0]);
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub ps = new IpfsPubSub(api);
        Partitioner partitioner = new Partitioner(n);
        monitors = new HeartbeatMonitor[n];
        for (int k=0;k<n;k++){ final int kk=k; monitors[k]=new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, msg->logEvent(msg)); }
        Thread checker = new Thread(() -> { while(true){ for(int i=0;i<n;i++) if(monitors[i]!=null) monitors[i].check(); try{Thread.sleep(Wire.VERIFICACAO_MS);}catch(InterruptedException e){break;} } });
        checker.setDaemon(true); checker.start();
        for (int k=0;k<n;k++){ final int kk=k; new Thread(() -> { try { ps.subscribe(Wire.topicData(kk), msg -> { String[] lines=msg.trim().split("\n"); String[] h=lines[0].trim().split(";"); int cnt=Integer.parseInt(h[8]); Batch b=Batch.decode(msg,cnt); if(monitors[kk]!=null) { monitors[kk].onHeartbeat(b.term,b.leader,b.hb); logEvent("RECEBIDO;p"+kk+";termo="+b.term+";hb="+b.hb+";ultimoSeq="+b.lastSeq+";n="+b.records.size()); } }); } catch(Exception e){} }, "sub-p"+kk).start(); }
        Random rnd = new Random();
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Produtor pronto (N=" + n + ")");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                if (p.length != 2) { System.out.println("Formato invalido"); continue; }
                double temp;
                try { temp = Double.parseDouble(p[1]); } catch (NumberFormatException e) { System.out.println("Temp invalida"); continue; }
                int k = partitioner.of(p[0]);
                if (monitors[k] != null && monitors[k].state() == HeartbeatMonitor.State.SUSPEITO) logEvent("AVISO: partição p" + k + " sem líder conhecido; leitura " + p[0] + " pode perder-se");
                ps.publish(Wire.topicIn(k), Wire.in(p[0], temp, 0, new VectorClock(partitioner.count())));
                logProdutor("p" + k + ";" + p[0] + ";" + temp);
                System.out.println("Enviado para p" + k);
            }
        }
    }
}
