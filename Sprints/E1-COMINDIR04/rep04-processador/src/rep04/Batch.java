package rep04;

import java.util.ArrayList;
import java.util.List;

public class Batch {
    public final String address;
    public final int port;
    public final long inc;
    public final long hb;
    public final long lastSeq;
    public final List<SensorRecord> records;

    public Batch(String address, int port, long inc, long hb, long lastSeq, List<SensorRecord> records) {
        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.records = records;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOTE;").append(address).append(";").append(port).append(";")
          .append(inc).append(";").append(hb).append(";").append(lastSeq).append(";")
          .append(records.size()).append("\n");
        for (SensorRecord r : records) {
            sb.append(r.toLine()).append("\n");
        }
        return sb.toString().trim();
    }

    public static Batch decode(String msg, int n) {
        String[] lines = msg.trim().split("\n");
        if (lines.length < 1) throw new IllegalArgumentException("LOTE vazio");
        String[] h = lines[0].trim().split(";");
        if (h.length != 7 || !h[0].equals("LOTE")) throw new IllegalArgumentException("Cabeçalho LOTE mal formado: " + lines[0]);
        String address = h[1];
        int port = Integer.parseInt(h[2]);
        long inc = Long.parseLong(h[3]);
        long hb = Long.parseLong(h[4]);
        long lastSeq = Long.parseLong(h[5]);
        int count = Integer.parseInt(h[6]);
        if (count != n) throw new IllegalArgumentException("n=" + count + " != esperado " + n);
        if (lines.length - 1 != count) throw new IllegalArgumentException("Linhas de registo (" + (lines.length - 1) + ") != n (" + count + ")");
        List<SensorRecord> records = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            records.add(SensorRecord.fromLine(lines[i], n));
        }
        return new Batch(address, port, inc, hb, lastSeq, records);
    }
}
