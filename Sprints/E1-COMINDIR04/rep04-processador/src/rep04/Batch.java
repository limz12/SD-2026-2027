package rep04;

import java.util.ArrayList;
import java.util.List;

public class Batch {

    // Informação do líder
    public final String address;
    public final int port;

    // Incarnation, heartbeat e última sequência
    public final long inc;
    public final long hb;
    public final long lastSeq;

    // Registos incluídos no lote
    public final List<SensorRecord> records;

    /**
     * Construtor de um lote.
     */
    public Batch(
            String address,
            int port,
            long inc,
            long hb,
            long lastSeq,
            List<SensorRecord> records) {

        if (address == null || address.isBlank()
                || address.contains(";")
                || address.contains("\n")
                || address.contains("\r")) {
            throw new IllegalArgumentException(
                "Endereço inválido"
            );
        }

        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException(
                "Porto inválido: " + port
            );
        }

        if (inc < 0 || hb < 0 || lastSeq < 0) {
            throw new IllegalArgumentException(
                "Inc, hb e lastSeq não podem ser negativos"
            );
        }

        if (records == null) {
            throw new IllegalArgumentException(
                "Lista de registos inválida"
            );
        }

        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;

        // Guardar uma lista independente
        this.records = new ArrayList<>(records);
    }

    /**
     * Codifica o lote numa mensagem de várias linhas.
     *
     * Cabeçalho:
     * LOTE;endereço;porto;inc;hb;ultimoSeq;n
     *
     * As linhas seguintes contêm os registos:
     * seq;sensor;temperatura;instante;L;V
     */
    public String encode() {

        StringBuilder sb = new StringBuilder();

        // Cabeçalho do lote
        sb.append("LOTE;")
          .append(address).append(";")
          .append(port).append(";")
          .append(inc).append(";")
          .append(hb).append(";")
          .append(lastSeq).append(";")
          .append(records.size());

        // Acrescentar uma linha por registo
        for (SensorRecord r : records) {

            sb.append("\n");
            sb.append(r.toLine());
        }

        return sb.toString();
    }

    /**
     * Descodifica uma mensagem LOTE.
     *
     * O argumento n representa o número de partições
     * do sistema, necessário para interpretar
     * os relógios vetoriais dos registos.
     *
     * Valida que o número de linhas de registos
     * corresponde ao valor indicado no cabeçalho.
     */
    public static Batch decode(String msg, int n) {

        if (msg == null || msg.isEmpty()) {
            throw new IllegalArgumentException(
                "Mensagem LOTE vazia"
            );
        }

        if (n <= 0) {
            throw new IllegalArgumentException(
                "Número de partições inválido: " + n
            );
        }

        // Separar as linhas da mensagem
        String[] linhas = msg.split("\\R", -1);

        // Aceitar uma quebra de linha final opcional
        int totalLinhas = linhas.length;

        if (totalLinhas > 1
                && linhas[totalLinhas - 1].isEmpty()) {
            totalLinhas--;
        }

        // Separar os campos do cabeçalho
        String[] f = linhas[0].split(";", -1);

        if (f.length != 7 || !f[0].equals("LOTE")) {
            throw new IllegalArgumentException(
                "Cabeçalho LOTE mal formado: " + linhas[0]
            );
        }

        try {

            String address = f[1];

            int port = Integer.parseInt(f[2]);
            long inc = Long.parseLong(f[3]);
            long hb = Long.parseLong(f[4]);
            long lastSeq = Long.parseLong(f[5]);

            // Quantidade de registos indicada no cabeçalho
            int quantidade = Integer.parseInt(f[6]);

            if (quantidade < 0) {
                throw new IllegalArgumentException(
                    "Quantidade de registos inválida"
                );
            }

            // Número de linhas após o cabeçalho
            int recebidos = totalLinhas - 1;

            if (recebidos != quantidade) {
                throw new IllegalArgumentException(
                    "Quantidade de registos incorreta: "
                    + "cabeçalho indica " + quantidade
                    + ", mas existem " + recebidos
                    + " linhas de registos"
                );
            }

            List<SensorRecord> records = new ArrayList<>();

            // Reconstruir cada registo
            for (int i = 1; i < totalLinhas; i++) {

                SensorRecord r = SensorRecord.fromLine(
                    linhas[i], n
                );

                records.add(r);
            }

            return new Batch(
                address,
                port,
                inc,
                hb,
                lastSeq,
                records
            );

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                "Campo numérico inválido no lote", e
            );
        }
    }
}
