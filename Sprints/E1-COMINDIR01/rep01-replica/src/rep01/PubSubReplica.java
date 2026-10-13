package rep01;

import java.io.*;
import java.util.TreeMap;

/**
 * Réplica da replicação com PUBSUB do IPFS.
 *
 * Uso: java -cp out rep01.PubSubReplica <id> [portoAPI=5001] [q]
 *   id       = identificador (escreve em replica-<id>.txt)
 *   portoAPI = porto da API do nó IPFS a que esta réplica se liga (5001 = nó 1, 5002 = nó 2)
 *   q        = modo silencioso (sem uma linha por registo)
 *
 * A lógica de aplicação (DUPLICADO / EM ESPERA / REJEITADO, TreeMap de retidos, ficheiro só
 * pela ordem do líder) é a mesma da MulticastReplica. Só muda a receção: em vez de
 * socket.receive(), subscribe() — que NÃO TERMINA e bloqueia à espera de cada linha JSON
 * que o daemon local vai escrevendo na resposta HTTP.
 */
public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";

    static RecordFile file;
    static long ultimo;
    static final TreeMap<Long, SensorRecord> retidos = new TreeMap<>();
    static boolean silencioso;

    static int recebidos, escritos, duplicados, rejeitados;
    static long primeiroRx, ultimoRx;

    static void aplicar(SensorRecord r, String etiqueta) throws IOException {
        file.append(r);
        ultimo = r.getSeq();
        escritos++;
        if (!silencioso) System.out.println(etiqueta + ": " + r.toLine());
    }

    // synchronized: é chamado pela thread do subscribe, e o estado é lido pela thread do monitor
    static synchronized void processar(String line) throws IOException {
        long agora = System.currentTimeMillis();
        if (recebidos == 0) primeiroRx = agora;
        ultimoRx = agora;
        recebidos++;

        SensorRecord r;
        try {
            r = SensorRecord.fromLine(line);
        } catch (IllegalArgumentException e) {
            rejeitados++;
            if (!silencioso) System.out.println("REJEITADO: " + line);
            return;
        }

        long seq = r.getSeq();
        if (seq <= ultimo || retidos.containsKey(seq)) {
            duplicados++;
            if (!silencioso) System.out.println("DUPLICADO: " + seq);
        } else if (seq == ultimo + 1) {
            aplicar(r, "Aplicado");
            while (!retidos.isEmpty() && retidos.firstKey() == ultimo + 1) {
                aplicar(retidos.pollFirstEntry().getValue(), "Aplicado (retido)");
            }
        } else {
            retidos.put(seq, r);
            if (!silencioso) System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
        }
    }

    // 2 s sem receber nada depois de ter recebido algo = fim da rajada -> resumo
    static synchronized void resumoSeInativa() {
        if (recebidos == 0 || System.currentTimeMillis() - ultimoRx < 2000) return;
        System.out.println("--- RESUMO ---");
        System.out.println("  recebidos=" + recebidos + " | escritos=" + escritos + " | duplicados=" + duplicados
                + " | rejeitados=" + rejeitados + " | retidos em memória=" + retidos.size());
        System.out.println("  1.º rx=" + primeiroRx + " ms | último rx=" + ultimoRx + " ms | duração="
                + (ultimoRx - primeiroRx) + " ms | último seq no ficheiro=" + ultimo);
        recebidos = escritos = duplicados = rejeitados = 0;
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        String porto = args.length > 1 ? args[1] : "5001";
        silencioso = args.length > 2 && args[2].equalsIgnoreCase("q");
        String api = "http://127.0.0.1:" + porto + "/api/v0";
        file = new RecordFile("replica-" + id + ".txt");
        try {
            ultimo = file.lastSeq();
            Thread monitor = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    resumoSeInativa();
                }
            });
            monitor.setDaemon(true);
            monitor.start();

            System.out.println("Réplica " + id + " subscreve '" + TOPIC + "' via " + api
                    + " (último seq no ficheiro = " + ultimo + ")");
            new IpfsPubSub(api).subscribe(TOPIC, line -> { // BLOQUEIA aqui: a resposta HTTP nunca termina
                try {
                    processar(line);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            // Daemon parado: ConnectException. Pubsub desativado no nó: "sub falhou (500)".
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}