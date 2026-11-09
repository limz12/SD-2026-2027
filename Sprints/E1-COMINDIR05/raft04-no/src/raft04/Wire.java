
package raft04;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Constantes, tópicos e formatos de mensagens
 * utilizados pelos processos do projeto raft04.
 *
 * IN;sensor;temperatura;L;V
 *
 * LOTE;term;leader;address;port;hb;lastSeq;lastTerm;n
 * + n linhas de SensorRecord.
 *
 * PEDIDO_VOTO e VOTO são transportados pelo
 * tópico de eleição de cada partição.
 *
 * Cada partição possui GRUPO = 3 nós:
 * n1, n2 e n3.
 */
public final class Wire {

    // Número máximo de registos retidos antes
    // de desencadear a recuperação.
    public static final int LIMITE_RETIDOS = 10;

    // Timeout TCP de ligação e de leitura.
    public static final int T_TCP_MS = 5000;

    // Primeiro porto TCP utilizado pelos nós.
    public static final int PORTO_BASE = 7000;

    // Limiar de temperatura para criar alarmes.
    public static final double LIMIAR_ALARME = 28.0;

    // Número de nós de cada partição.
    // Identificadores válidos: 1, 2 e 3.
    public static final int GRUPO = 3;

    /**
     * Período de publicação dos heartbeats.
     *
     * Propriedade:
     * -Draft04.periodo=<ms>
     *
     * Valor por omissão: 2000 ms.
     */
    public static final long PERIODO_HB_MS =
            Long.getLong("raft04.periodo", 2000L);

    /**
     * Fator utilizado pelo detetor de falhas.
     *
     * Propriedade:
     * -Draft04.fator=<n>
     *
     * Valor por omissão: 3.
     */
    public static final int FATOR_TIMEOUT =
            Integer.getInteger("raft04.fator", 3);

    // Intervalo entre verificações dos monitores.
    public static final long VERIFICACAO_MS = 200L;

    /**
     * Tempo mínimo para iniciar uma eleição.
     *
     * Por omissão:
     * 3 * PERIODO_HB_MS
     */
    public static final long ELEICAO_MIN_MS =
            Long.getLong(
                "raft04.eleicaoMin",
                3L * PERIODO_HB_MS
            );

    /**
     * Tempo máximo para iniciar uma eleição.
     *
     * Por omissão:
     * 6 * PERIODO_HB_MS
     */
    public static final long ELEICAO_MAX_MS =
            Long.getLong(
                "raft04.eleicaoMax",
                6L * PERIODO_HB_MS
            );

    private Wire() {
    }

    /**
     * Tópico de entrada da partição k.
     *
     * Este tópico não é afetado pelo isolamento.
     */
    public static String topicIn(int k) {
        return "raft04-in-p" + k;
    }

    /**
     * Tópico de dados e heartbeats da partição k.
     */
    public static String topicData(int k) {
        return "raft04-p" + k;
    }

    /**
     * Tópico utilizado nas eleições.
     */
    public static String topicElection(int k) {
        return "raft04-eleicao-p" + k;
    }

    /**
     * Porto TCP de recuperação do nó i
     * da partição k.
     *
     * Os nós são identificados por 1, 2 e 3.
     *
     * Exemplos:
     *
     * p0:
     * n1 -> 7000
     * n2 -> 7001
     * n3 -> 7002
     *
     * p1:
     * n1 -> 7003
     * n2 -> 7004
     * n3 -> 7005
     */
    public static int nodePort(int k, int i) {

        if (k < 0) {
            throw new IllegalArgumentException(
                "Partição inválida: " + k
            );
        }

        if (i < 1 || i > GRUPO) {
            throw new IllegalArgumentException(
                "Identificador de nó inválido: " + i
            );
        }

        long porto =
                (long) PORTO_BASE
                + (long) k * GRUPO
                + (i - 1);

        if (porto > 65535) {
            throw new IllegalArgumentException(
                "Porto TCP fora do intervalo válido"
            );
        }

        return (int) porto;
    }

    /**
     * Verifica se o nó i da partição k
     * está isolado.
     *
     * Um nó está isolado enquanto existir
     * na pasta de trabalho o ficheiro:
     *
     * isolar-p<k>-n<i>
     *
     * O resultado é consultado dinamicamente.
     *
     * A aplicação deve chamar este método
     * antes de publicar ou processar mensagens
     * dos tópicos de dados e eleição.
     *
     * Deve também verificar o isolamento
     * nas operações de recuperação TCP.
     *
     * O tópico IN não é afetado.
     */
    public static boolean isolated(int k, int i) {

        if (k < 0) {
            throw new IllegalArgumentException(
                "Partição inválida: " + k
            );
        }

        if (i < 1 || i > GRUPO) {
            throw new IllegalArgumentException(
                "Identificador de nó inválido: " + i
            );
        }

        return Files.exists(
            Paths.get(
                "isolar-p" + k + "-n" + i
            )
        );
    }

    /**
     * Constrói uma mensagem IN.
     *
     * Formato:
     * IN;sensor;temperatura;L;V
     *
     * O protocolo IN mantém-se inalterado.
     */
    public static String in(
            String sensor,
            double temp,
            long lamport,
            VectorClock v) {

        return "IN;"
                + sensor + ";"
                + temp + ";"
                + lamport + ";"
                + v;
    }

    /**
     * Obtém o tipo da mensagem.
     *
     * Exemplos:
     * IN
     * LOTE
     * PEDIDO_VOTO
     * VOTO
     */
    public static String type(String msg) {

        if (msg == null) {
            throw new IllegalArgumentException(
                "Mensagem nula"
            );
        }

        String m = msg.trim();

        int i = m.indexOf(';');

        if (i <= 0) {
            throw new IllegalArgumentException(
                "Mensagem sem tipo: " + msg
            );
        }

        return m.substring(0, i);
    }

    /**
     * Devolve o conteúdo da mensagem,
     * removendo o tipo inicial.
     */
    public static String body(String msg) {

        if (msg == null) {
            throw new IllegalArgumentException(
                "Mensagem nula"
            );
        }

        String m = msg.trim();

        int i = m.indexOf(';');

        if (i <= 0) {
            throw new IllegalArgumentException(
                "Mensagem sem tipo: " + msg
            );
        }

        return m.substring(i + 1);
    }
}
