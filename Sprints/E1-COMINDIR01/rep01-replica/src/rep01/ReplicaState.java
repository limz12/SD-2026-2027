package rep01;

import java.io.IOException;
import java.util.TreeMap;

/** Mantém a invariante: o ficheiro só contém o prefixo contínuo do líder. */
final class ReplicaState {
    private final RecordFile file;
    private final TreeMap<Long, SensorRecord> waiting = new TreeMap<>();
    private long last;
    private long received, written, duplicates;
    private long burstFirst = -1, burstLast = -1, burstReceived;

    ReplicaState(RecordFile file) throws IOException { this.file = file; this.last = file.lastSeq(); }

    synchronized void accept(String text) {
        try { acceptRecord(SensorRecord.fromLine(text)); }
        catch (IllegalArgumentException e) { System.out.println("REJEITADO: " + text); }
        catch (IOException e) { System.out.println("IO: " + e.getMessage()); }
    }

    private void acceptRecord(SensorRecord r) throws IOException {
        received++;
        if (r.getSensorId().equals("SIM")) { long now = System.currentTimeMillis(); if (burstFirst < 0) burstFirst = now; burstLast = now; burstReceived++; }
        if (r.getSeq() <= last || waiting.containsKey(r.getSeq())) {
            duplicates++; System.out.println("DUPLICADO: " + r.getSeq()); return;
        }
        if (r.getSeq() > last + 1) {
            waiting.put(r.getSeq(), r);
            System.out.println("EM ESPERA: " + r.getSeq() + " (falta o registo " + (last + 1) + ")");
            return;
        }
        appendAndAdvance(r);
        while (waiting.containsKey(last + 1)) appendAndAdvance(waiting.remove(last + 1));
    }

    private void appendAndAdvance(SensorRecord r) throws IOException {
        file.append(r); last = r.getSeq(); written++; System.out.println("Aplicado: " + r.toLine());
    }

    synchronized void printStats() {
        System.out.println("ESTATÍSTICAS: recebidos=" + received + ", escritos=" + written + ", duplicados=" + duplicates + ", em espera=" + waiting.size());
        if (burstFirst >= 0) System.out.println("RAJADA na réplica: primeiro=" + burstFirst + ", último=" + burstLast + ", recebidos=" + burstReceived);
    }
}
