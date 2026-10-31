package rep04;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lote de registos transmitido pelo líder num único heartbeat.
 *
 * Formato (uma linha de cabeçalho e n linhas de registos):
 * LOTE;endereco;porto;inc;hb;lastSeq;n\n
 * seq;sensor;temp;timestamp;L;V\n ...
 */
public final class Batch {

    private final String address;
    private final int port;
    private final long inc;
    private final long hb;
    private final long lastSeq;
    private final List<SensorRecord> records;

    public Batch(String address, int port, long inc, long hb,
                 long lastSeq, List<SensorRecord> records) {

        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Endereço ausente no lote");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Porto inválido no lote: " + port);
        }
        if (records == null) {
            throw new IllegalArgumentException("Lista de registos ausente no lote");
        }

        this.address  = address;
        this.port     = port;
        this.inc      = inc;
        this.hb       = hb;
        this.lastSeq  = lastSeq;
        this.records  = Collections.unmodifiableList(new ArrayList<>(records));
    }

    public String getAddress() { return address; }

    public int getPort() { return port; }

    public long getInc() { return inc; }

    public long getHb() { return hb; }

    public long getLastSeq() { return lastSeq; }

    public List<SensorRecord> getRecords() { return records; }

    public int size() { return records.size(); }

    /**
     * Codifica o lote numa mensagem multi-linha:
     * LOTE;endereco;porto;inc;hb;lastSeq;n\n + n linhas de registos.
     */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOTE;")
          .append(address).append(';')
          .append(port).append(';')
          .append(inc).append(';')
          .append(hb).append(';')
          .append(lastSeq).append(';')
          .append(records.size())
          .append('\n');
        for (SensorRecord r : records) {
            sb.append(r.toLine()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Reconstrói um lote a partir da mensagem multi-linha.
     *
     * N indica o número de partições do sistema e é utilizado
     * para reconstruir o relógio vetorial de cada registo.
     *
     * Lança IllegalArgumentException quando a mensagem está mal formada.
     */
    public static Batch decode(String msg, int N) {
        if (msg == null) {
            throw new IllegalArgumentException("Mensagem ausente");
        }

        String[] linhas = msg.split("\n", -1);

        // Remover linhas vazias no fim (ex.: sequências \n\n ou \r\n)
        int fim = linhas.length;
        while (fim > 0 && linhas[fim - 1].isBlank()) {
            fim--;
        }

        if (fim < 1) {
            throw new IllegalArgumentException("Lote sem cabeçalho");
        }

        String[] h = linhas[0].trim().split(";", -1);

        if (h.length != 7 || !h[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho de lote mal formado: " + linhas[0]);
        }

        String address;
        int port;
        long inc;
        long hb;
        long lastSeq;
        int n;

        try {
            address = h[1];
            port    = Integer.parseInt(h[2]);
            inc     = Long.parseLong(h[3]);
            hb      = Long.parseLong(h[4]);
            lastSeq = Long.parseLong(h[5]);
            n       = Integer.parseInt(h[6]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Campo numérico inválido no cabeçalho: " + linhas[0]);
        }

        if (n < 0) {
            throw new IllegalArgumentException("Número de registos negativo: " + n);
        }

        if (fim - 1 != n) {
            throw new IllegalArgumentException(
                "Cabeçalho anuncia " + n + " registos, mas há " + (fim - 1) + " linhas");
        }

        List<SensorRecord> registos = new ArrayList<>(n);

        for (int i = 1; i <= n; i++) {
            registos.add(SensorRecord.fromLine(linhas[i], N));
        }

        return new Batch(address, port, inc, hb, lastSeq, registos);
    }
}
