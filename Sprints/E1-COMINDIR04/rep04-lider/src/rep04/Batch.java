package rep04;

import java.util.ArrayList;
import java.util.List;

/**
 * Um LOTE: cabeçalho + n registos. É, ao mesmo tempo, a mensagem de dados e o
 * HEARTBEAT do líder:
 * é emitido a cada Δ mesmo quando n = 0 (heartbeat puro).
 *
 * LOTE;<endereço>;<porto>;<inc>;<hb>;<ultimoSeq>;<n>
 * <seq>;<sensor>;<temperatura>;<instante>;<L>;<V> (n linhas, formato dos
 * ficheiros)
 *
 * inc = incarnação do líder (instante de arranque, ms): mudar de inc = reinício
 * hb = nº do heartbeat dentro da incarnação, a partir de 1: um salto =
 * heartbeat perdido
 * ultimoSeq = último seq atribuído pelo líder quando o lote foi fechado (lá
 * estão TODOS os registos
 * até ele, neste lote ou nos anteriores); é o que permite detetar a perda do
 * último registo.
 */
public final class Batch {
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
        sb.append("LOTE;").append(address).append(';').append(port).append(';').append(inc).append(';')
                .append(hb).append(';').append(lastSeq).append(';').append(records.size());
        for (SensorRecord r : records) {
            sb.append('\n').append(r.toLine());
        }
        return sb.toString();
    }

    // dim = dimensão do vetor dos registos (N); dim <= 0 deduz-a de cada linha
    // (réplicas, que não conhecem N).
    // Lança IllegalArgumentException se o cabeçalho for inválido ou se o nº de
    // linhas não coincidir com o n do cabeçalho.
    public static Batch decode(String msg, int dim) {
        String[] linhas = msg.split("\n", -1);
        int fim = linhas.length;
        while (fim > 1 && linhas[fim - 1].isBlank())
            fim--; // ignora linhas vazias finais
        String[] h = linhas[0].trim().split(";");
        if (h.length != 7 || !h[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho de LOTE inválido: " + linhas[0]);
        }
        int n = Integer.parseInt(h[6]);
        if (n < 0 || fim - 1 != n) {
            throw new IllegalArgumentException("LOTE com " + (fim - 1) + " linhas de registo, cabeçalho diz n=" + n);
        }
        List<SensorRecord> regs = new ArrayList<>();
        for (int i = 1; i < fim; i++) {
            regs.add(dim > 0 ? SensorRecord.fromLine(linhas[i], dim) : SensorRecord.fromLine(linhas[i]));
        }
        return new Batch(h[1], Integer.parseInt(h[2]), Long.parseLong(h[3]), Long.parseLong(h[4]),
                Long.parseLong(h[5]), regs);
    }

    public static Batch decode(String msg) {
        return decode(msg, 0);
    }

    // só o cabeçalho (o produtor não precisa dos registos): devolve um lote com a
    // lista de registos VAZIA;
    // o n declarado no cabeçalho lê-se com headerCount().
    public static Batch decodeHeader(String msg) {
        String[] h = msg.split("\n", 2)[0].trim().split(";");
        if (h.length != 7 || !h[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho de LOTE inválido: " + h[0]);
        }
        Integer.parseInt(h[6]); // valida
        return new Batch(h[1], Integer.parseInt(h[2]), Long.parseLong(h[3]), Long.parseLong(h[4]),
                Long.parseLong(h[5]), new ArrayList<>());
    }

    // n declarado no cabeçalho de uma mensagem LOTE
    public static int headerCount(String msg) {
        return Integer.parseInt(msg.split("\n", 2)[0].trim().split(";")[6]);
    }
}