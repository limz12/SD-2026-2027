package raft04;

import java.util.ArrayList;
import java.util.List;

public final class Batch {
    public final long term;
    public final int leader;
    public final String address;
    public final int port;
    public final long hb;
    public final long lastSeq;
    public final long lastTerm;
    public final List<SensorRecord> records;

    public Batch(long term, int leader, String address, int port, long hb, long lastSeq, long lastTerm,
            List<SensorRecord> records) {
        if (term < 0 || leader < 1 || address == null || port < 1 || port > 65535
                || hb < 1 || lastSeq < 0 || lastTerm < 0 || records == null) {
            throw new IllegalArgumentException("Campos do LOTE fora dos limites");
        }
        this.term = term;
        this.leader = leader;
        this.address = address;
        this.port = port;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
        this.records = new ArrayList<>(records);
    }

    public String encode() {
        StringBuilder out = new StringBuilder("LOTE;").append(term).append(';').append(leader).append(';')
                .append(address).append(';').append(port).append(';').append(hb).append(';')
                .append(lastSeq).append(';').append(lastTerm).append(';').append(records.size());
        for (SensorRecord record : records) out.append('\n').append(record.toLine());
        return out.toString();
    }

    public static Batch decode(String message, int dimension) {
        String[] lines = message.split("\n", -1);
        int end = lines.length;
        while (end > 1 && lines[end - 1].isBlank()) end--;
        String[] h = lines[0].trim().split(";", -1);
        if (h.length != 9 || !"LOTE".equals(h[0])) throw new IllegalArgumentException("Cabeçalho de LOTE inválido");
        try {
            int count = Integer.parseInt(h[8]);
            if (count < 0 || end - 1 != count) throw new IllegalArgumentException("Contagem do LOTE inválida");
            List<SensorRecord> records = new ArrayList<>();
            for (int i = 1; i < end; i++) {
                records.add(dimension > 0 ? SensorRecord.fromLine(lines[i], dimension) : SensorRecord.fromLine(lines[i]));
            }
            return new Batch(Long.parseLong(h[1]), Integer.parseInt(h[2]), h[3], Integer.parseInt(h[4]),
                    Long.parseLong(h[5]), Long.parseLong(h[6]), Long.parseLong(h[7]), records);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cabeçalho de LOTE inválido", e);
        }
    }

    public static Batch decode(String message) { return decode(message, 0); }

    public static Batch decodeHeader(String message) {
        String[] h = message.split("\n", 2)[0].trim().split(";", -1);
        if (h.length != 9 || !"LOTE".equals(h[0])) throw new IllegalArgumentException("Cabeçalho de LOTE inválido");
        try {
            if (Integer.parseInt(h[8]) < 0) throw new IllegalArgumentException("Contagem do LOTE inválida");
            return new Batch(Long.parseLong(h[1]), Integer.parseInt(h[2]), h[3], Integer.parseInt(h[4]),
                    Long.parseLong(h[5]), Long.parseLong(h[6]), Long.parseLong(h[7]), List.of());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cabeçalho de LOTE inválido", e);
        }
    }

    public static int headerCount(String message) {
        String[] h = message.split("\n", 2)[0].trim().split(";", -1);
        if (h.length != 9 || !"LOTE".equals(h[0])) throw new IllegalArgumentException("Cabeçalho de LOTE inválido");
        int count = Integer.parseInt(h[8]);
        if (count < 0) throw new IllegalArgumentException("Contagem do LOTE inválida");
        return count;
    }
}
