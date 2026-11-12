package raft04;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Nomes dos tópicos, parâmetros do heartbeat/eleição e formato das mensagens
 * (contrato entre todos os processos).
 *
 * IN;<sensor>;<temperatura>;<L>;<V> (inalterado)
 * LOTE;<termo>;<lider>;<endereço>;<porto>;<hb>;<ultimoSeq>;<ultimoTermo>;<n> + n linhas
 * PEDIDO_VOTO;<termo>;<candidato>;<ultimoSeq>;<ultimoTermo>
 * VOTO;<termo>;<eleitor>;<candidato>;<S|N>
 *
 * Parâmetros (podem ser alterados com -Draft04.periodo, -Draft04.fator,
 * -Draft04.eleicaoMin e -Draft04.eleicaoMax, e TÊM de ser iguais em todos os
 * processos de um ensaio):
 * - PERIODO_HB_MS = Δ (por omissão 2000);
 * - FATOR_TIMEOUT (por omissão 3): TIMEOUT = FATOR × Δ, nos detetores do
 *   produtor e do processador;
 * - ELEICAO_MIN_MS e ELEICAO_MAX_MS (por omissão 3 × Δ e 6 × Δ): intervalo do
 *   sorteio uniforme do prazo de eleição de cada nó;
 * - VERIFICACAO_MS (200): período da thread de verificação.
 *
 * isolated(k, i) simula uma PARTIÇÃO DE REDE: devolve true enquanto existir,
 * na pasta de trabalho, um ficheiro chamado isolar-p<k>-n<i>. Um nó isolado
 * não publica nem processa mensagens dos tópicos raft04-p<k> e
 * raft04-eleicao-p<k>, e as suas ligações TCP de recuperação, como cliente ou
 * servidor, falham. O tópico raft04-in-p<k> NÃO é afetado.
 */
public final class Wire {
    public static final int LIMITE_RETIDOS = 10; // acima deste número de retidos, o nó recupera por TCP
    public static final int T_TCP_MS = 5000; // tempo máximo de ligação e de espera por cada linha
    public static final int PORTO_BASE = 7100; // porto TCP de recuperação do nó i da partição k: 7100 + k*10 + i
    public static final double LIMIAR_ALARME = 28.0; // temperatura a partir da qual o processador gera um alarme

    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L); // Δ
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3); // TIMEOUT = FATOR × Δ
    public static final long VERIFICACAO_MS = 200;
    public static final long ELEICAO_MIN_MS = Long.getLong("raft04.eleicaoMin", 3 * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS = Long.getLong("raft04.eleicaoMax", 6 * PERIODO_HB_MS);
    public static final int GRUPO = 3; // G: nós por partição (n1, n2 e n3)

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

    // porto TCP de recuperação do nó i da partição k
    public static int nodePort(int k, int i) {
        return PORTO_BASE + k * 10 + i;
    }

    // simulação de partição de rede (ver comentário da classe)
    public static boolean isolated(int k, int i) {
        return Files.exists(Paths.get("isolar-p" + k + "-n" + i));
    }

    // o produtor envia sempre L = 0 e V = (0,...,0): não observa registo algum
    public static String in(String sensor, double temp, long lamport, VectorClock v) {
        return "IN;" + sensor + ";" + temp + ";" + lamport + ";" + v;
    }

    // tipo da mensagem (IN, LOTE, PEDIDO_VOTO, VOTO); lança
    // IllegalArgumentException se não houver tipo
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
