package raft04;

/**
 * Nomes dos tópicos, parâmetros do heartbeat e formato das mensagens (contrato
 * entre todos os processos).
 * IN;<sensor>;<temperatura>;<L>;<V> (inalterado)
 * LOTE;<endereço>;<porto>;<inc>;<hb>;<ultimoSeq>;<n> + n linhas de registo (ver
 * Batch)
 * O REG da COMINDIR03 deixou de existir: o LOTE é a única mensagem de dados e é
 * também o heartbeat.
 */
public final class Wire {
    public static final int LIMITE_RETIDOS = 10; // acima deste número de retidos, a réplica recupera
    public static final int T_TCP_MS = 5000; // tempo máximo de ligação e de espera por cada linha
    public static final int PORTO_BASE = 7000; // porto de recuperação do líder da partição k: 7000 + k
    public static final double LIMIAR_ALARME = 28.0; // temperatura a partir da qual o processador gera um alarme

    // Parâmetros do detetor. O período e o fator podem ser alterados com
    // -Drep04.periodo=<ms> e
    // -Drep04.fator=<n>, e TÊM de ser iguais em todos os processos (ensaio 8(e)).
    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L); // Δ
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3); // TIMEOUT = FATOR × Δ
    public static final long VERIFICACAO_MS = 200; // período de verificação
    public static final int ELEICAO_MIN_MS = Integer.getInteger("raft04.eleicaoMin", (int)(3 * PERIODO_HB_MS));
    public static final int ELEICAO_MAX_MS = Integer.getInteger("raft04.eleicaoMax", (int)(6 * PERIODO_HB_MS));
    public static final int GRUPO = 3;

    private Wire() {
    }

    public static String topicIn(int k) {
        return "raft04-in-p" + k;
    }

    public static String topicData(int k) {
        return "raft04-p" + k;
    }

    public static String topicElection(int k) {
        return "raft04-eleicao-p" + k;
    }

    public static int nodePort(int k, int i) {
        return 7000 + k*10 + i;
    }

    public static boolean isolated(int k, int i) {
        return new java.io.File("isolar-p" + k + "-n" + i).exists();
    }

    // o produtor envia sempre L = 0 e V = (0,...,0): não observa registo algum
    public static String in(String sensor, double temp, long lamport, VectorClock v) {
        return "IN;" + sensor + ";" + temp + ";" + lamport + ";" + v;
    }

    // tipo da mensagem (IN, LOTE); lança IllegalArgumentException se não houver
    // tipo
    public static String type(String msg) {
        String m = msg.trim();
        int i = m.indexOf(';');
        if (i <= 0) {
            throw new IllegalArgumentException("Mensagem sem tipo: " + msg);
        }
        return m.substring(0, i);
    }

    // conteúdo da mensagem sem o tipo
    public static String body(String msg) {
        String m = msg.trim();
        return m.substring(m.indexOf(';') + 1);
    }
}