package raft04;

import java.util.ArrayList;
import java.util.List;

/**
 * Um LOTE: cabeçalho + n registos. É, ao mesmo tempo, a mensagem de dados e o
 * HEARTBEAT do líder: é emitido a cada Δ mesmo quando n = 0 (heartbeat puro).
 * É também a afirmação da liderança: o termo e o líder do cabeçalho dizem aos
 * outros nós quem lidera e em que termo.
 *
 * LOTE;<termo>;<lider>;<endereço>;<porto>;<hb>;<ultimoSeq>;<ultimoTermo>;<n>
 * <seq>;<termo>;<sensor>;<temperatura>;<instante>;<L>;<V> (n linhas, formato dos
 * ficheiros)
 *
 * termo = termo da liderança que fechou o lote
 * lider = índice (1..G) do nó que lidera a partição nesse termo
 * hb = nº do heartbeat dentro da liderança, a partir de 1: um salto dentro do
 * mesmo termo = heartbeat perdido
 * ultimoSeq = último seq atribuído pelo líder quando o lote foi fechado (lá
 * estão TODOS os registos até ele, neste lote ou nos anteriores)
 * ultimoTermo = termo do registo ultimoSeq
 */
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
        this.term = term;
        this.leader = leader;
        this.address = address;
        this.port = port;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
        this.records = records;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOTE;").append(term).append(';').append(leader).append(';').append(address).append(';')
                .append(port).append(';').append(hb).append(';').append(lastSeq).append(';').append(lastTerm)
                .append(';').append(records.size());
        for (SensorRecord r : records) {
            sb.append('\n').append(r.toLine());
        }
        return sb.toString();
    }

    // dim = dimensão do vetor dos registos (N). Lança IllegalArgumentException se
    // o cabeçalho for inválido ou se o nº de linhas não coincidir com o n do
    // cabeçalho.
    public static Batch decode(String msg, int dim) {
        String[] linhas = msg.split("\n", -1);
        int fim = linhas.length;
        while (fim > 1 && linhas[fim - 1].isBlank())
            fim--; // ignora linhas vazias finais
        String[] h = linhas[0].trim().split(";");
        if (h.length != 9 || !h[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho de LOTE inválido: " + linhas[0]);
        }
        int n = Integer.parseInt(h[8]);
        if (n < 0 || fim - 1 != n) {
            throw new IllegalArgumentException("LOTE com " + (fim - 1) + " linhas de registo, cabeçalho diz n=" + n);
        }
        List<SensorRecord> regs = new ArrayList<>();
        for (int i = 1; i < fim; i++) {
            regs.add(dim > 0 ? SensorRecord.fromLine(linhas[i], dim) : SensorRecord.fromLine(linhas[i]));
        }
        return new Batch(Long.parseLong(h[1]), Integer.parseInt(h[2]), h[3], Integer.parseInt(h[4]),
                Long.parseLong(h[5]), Long.parseLong(h[6]), Long.parseLong(h[7]), regs);
    }

    public static Batch decode(String msg) {
        return decode(msg, 0);
    }

    // só o cabeçalho (o produtor não precisa dos registos): devolve um lote com
    // a lista de registos VAZIA; o n declarado no cabeçalho lê-se com
    // headerCount().
    public static Batch decodeHeader(String msg) {
        String[] h = msg.split("\n", 2)[0].trim().split(";");
        if (h.length != 9 || !h[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho de LOTE inválido: " + h[0]);
        }
        Integer.parseInt(h[8]); // valida
        return new Batch(Long.parseLong(h[1]), Integer.parseInt(h[2]), h[3], Integer.parseInt(h[4]),
                Long.parseLong(h[5]), Long.parseLong(h[6]), Long.parseLong(h[7]), new ArrayList<>());
    }

    // n declarado no cabeçalho de uma mensagem LOTE
    public static int headerCount(String msg) {
        return Integer.parseInt(msg.split("\n", 2)[0].trim().split(";")[8]);
    }
}
