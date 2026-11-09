
package raft04;

import java.util.ArrayList;
import java.util.List;

/**
 * Um LOTE contém um cabeçalho e n registos.
 *
 * É simultaneamente uma mensagem de dados
 * e um heartbeat do líder, sendo publicado
 * periodicamente mesmo quando n = 0.
 *
 * Cabeçalho:
 * LOTE;term;leader;address;port;hb;lastSeq;lastTerm;n
 *
 * Registos:
 * Cada linha utiliza o formato de SensorRecord,
 * incluindo o termo em que o registo foi criado.
 *
 * term     = termo atual do líder.
 * leader   = identificador do nó líder.
 * address  = endereço do líder.
 * port     = porto TCP de recuperação do líder.
 * hb       = número do heartbeat.
 * lastSeq  = última sequência conhecida pelo líder.
 * lastTerm = termo do último registo do log.
 * n        = número de registos incluídos no lote.
 *
 * Um lote vazio (n = 0) é válido e mantém
 * lastSeq e lastTerm no cabeçalho.
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

    /**
     * Construtor de um lote.
     */
    public Batch(
            long term,
            int leader,
            String address,
            int port,
            long hb,
            long lastSeq,
            long lastTerm,
            List<SensorRecord> records) {

        if (term < 0 || hb < 0
                || lastSeq < 0 || lastTerm < 0) {

            throw new IllegalArgumentException(
                "Termo, heartbeat ou sequência inválidos"
            );
        }

        if (leader < 1 || leader > Wire.GRUPO) {
            throw new IllegalArgumentException(
                "Identificador do líder inválido: " + leader
                + " (deve estar entre 1 e " + Wire.GRUPO + ")"
            );
        }

        if (address == null || address.isBlank()
                || address.contains(";")
                || address.contains("\n")
                || address.contains("\r")) {

            throw new IllegalArgumentException(
                "Endereço do líder inválido"
            );
        }

        if (port <= 0 || port > 65535) {

            throw new IllegalArgumentException(
                "Porto inválido: " + port
            );
        }

        if (records == null || records.contains(null)) {

            throw new IllegalArgumentException(
                "Lista de registos inválida"
            );
        }

        this.term = term;
        this.leader = leader;
        this.address = address;
        this.port = port;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;

        // Criar uma lista independente da recebida
        this.records = new ArrayList<>(records);
    }

    /**
     * Codifica um lote numa mensagem de várias linhas.
     *
     * Cabeçalho:
     * LOTE;term;leader;address;port;hb;lastSeq;lastTerm;n
     *
     * Cada registo é acrescentado numa linha própria.
     *
     * O método SensorRecord.toLine() deve incluir
     * o termo individual de cada registo.
     */
    public String encode() {

        StringBuilder sb = new StringBuilder();

        // Construir o cabeçalho
        sb.append("LOTE;")
          .append(term).append(';')
          .append(leader).append(';')
          .append(address).append(';')
          .append(port).append(';')
          .append(hb).append(';')
          .append(lastSeq).append(';')
          .append(lastTerm).append(';')
          .append(records.size());

        // Acrescentar uma linha por registo
        for (SensorRecord r : records) {

            sb.append('\n');
            sb.append(r.toLine());
        }

        return sb.toString();
    }

    /**
     * Descodifica uma mensagem LOTE completa.
     *
     * dim = dimensão do relógio vetorial.
     *
     * Se dim > 0, utiliza essa dimensão.
     * Se dim <= 0, a dimensão é deduzida
     * pela implementação de SensorRecord.fromLine().
     *
     * Valida que a quantidade de linhas de
     * registos corresponde ao n do cabeçalho.
     */
    public static Batch decode(String msg, int dim) {

        if (msg == null || msg.isEmpty()) {

            throw new IllegalArgumentException(
                "Mensagem LOTE vazia"
            );
        }

        // Separar todas as linhas
        String[] linhas = msg.split("\\R", -1);

        int fim = linhas.length;

        // Aceitar uma quebra de linha final opcional
        if (fim > 1 && linhas[fim - 1].isEmpty()) {
            fim--;
        }

        // Separar os campos do cabeçalho
        String[] h = linhas[0].trim().split(";", -1);

        if (h.length != 9 || !h[0].equals("LOTE")) {

            throw new IllegalArgumentException(
                "Cabeçalho de LOTE inválido: " + linhas[0]
            );
        }

        try {

            long term = Long.parseLong(h[1]);
            int leader = Integer.parseInt(h[2]);

            String address = h[3];

            int port = Integer.parseInt(h[4]);

            long hb = Long.parseLong(h[5]);
            long lastSeq = Long.parseLong(h[6]);
            long lastTerm = Long.parseLong(h[7]);

            // Número de registos declarado no cabeçalho
            int n = Integer.parseInt(h[8]);

            if (n < 0) {

                throw new IllegalArgumentException(
                    "Número de registos negativo: " + n
                );
            }

            // Verificar a quantidade de linhas
            int recebidos = fim - 1;

            if (recebidos != n) {

                throw new IllegalArgumentException(
                    "LOTE com " + recebidos
                    + " linhas de registo, "
                    + "cabeçalho indica n=" + n
                );
            }

            // Reconstruir os registos
            List<SensorRecord> regs = new ArrayList<>();

            for (int i = 1; i < fim; i++) {

                if (linhas[i].isBlank()) {

                    throw new IllegalArgumentException(
                        "Linha de registo vazia: " + i
                    );
                }

                SensorRecord r;

                if (dim > 0) {

                    r = SensorRecord.fromLine(
                        linhas[i], dim
                    );

                } else {

                    r = SensorRecord.fromLine(
                        linhas[i]
                    );
                }

                regs.add(r);
            }

            // Construir o lote descodificado
            return new Batch(
                term,
                leader,
                address,
                port,
                hb,
                lastSeq,
                lastTerm,
                regs
            );

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                "Campo numérico inválido no LOTE",
                e
            );
        }
    }

    /**
     * Descodifica um lote sem indicar
     * explicitamente a dimensão do vetor.
     */
    public static Batch decode(String msg) {

        return decode(msg, 0);
    }

    /**
     * Descodifica apenas o cabeçalho de um LOTE.
     *
     * Útil para o produtor, que precisa de
     * identificar o líder e acompanhar heartbeats,
     * mas não necessita de reconstruir os registos.
     *
     * Devolve um Batch com records vazio.
     *
     * A quantidade original de registos pode
     * ser consultada através de headerCount(msg).
     */
    public static Batch decodeHeader(String msg) {

        if (msg == null || msg.isEmpty()) {

            throw new IllegalArgumentException(
                "Mensagem LOTE vazia"
            );
        }

        // Obter apenas a primeira linha
        String primeiraLinha = msg.split("\\R", 2)[0];

        String[] h = primeiraLinha.trim().split(";", -1);

        if (h.length != 9 || !h[0].equals("LOTE")) {

            throw new IllegalArgumentException(
                "Cabeçalho de LOTE inválido: "
                + primeiraLinha
            );
        }

        try {

            long term = Long.parseLong(h[1]);
            int leader = Integer.parseInt(h[2]);

            String address = h[3];

            int port = Integer.parseInt(h[4]);

            long hb = Long.parseLong(h[5]);
            long lastSeq = Long.parseLong(h[6]);
            long lastTerm = Long.parseLong(h[7]);

            int n = Integer.parseInt(h[8]);

            if (n < 0) {

                throw new IllegalArgumentException(
                    "Número de registos inválido: " + n
                );
            }

            // A lista fica vazia porque apenas
            // queremos a informação do cabeçalho
            return new Batch(
                term,
                leader,
                address,
                port,
                hb,
                lastSeq,
                lastTerm,
                new ArrayList<>()
            );

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                "Campo numérico inválido no cabeçalho",
                e
            );
        }
    }

    /**
     * Devolve o número de registos declarado
     * no cabeçalho da mensagem LOTE.
     *
     * Não descodifica as linhas dos registos.
     */
    public static int headerCount(String msg) {

        if (msg == null || msg.isEmpty()) {

            throw new IllegalArgumentException(
                "Mensagem LOTE vazia"
            );
        }

        String primeiraLinha = msg.split("\\R", 2)[0];

        String[] h = primeiraLinha.trim().split(";", -1);

        if (h.length != 9 || !h[0].equals("LOTE")) {

            throw new IllegalArgumentException(
                "Cabeçalho de LOTE inválido"
            );
        }

        try {

            int n = Integer.parseInt(h[8]);

            if (n < 0) {

                throw new IllegalArgumentException(
                    "Número de registos inválido: " + n
                );
            }

            return n;

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                "Quantidade de registos inválida",
                e
            );
        }
    }
}
