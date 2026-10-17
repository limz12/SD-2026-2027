package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/**
 * Réplica da replicação com MULTICAST IP.
 *
 * Uso: java -cp out rep01.MulticastReplica <id> [q]
 *   id = identificador (escreve em replica-<id>.txt); "q" = modo silencioso (sem uma linha por registo,
 *   útil nas rajadas, porque imprimir 1000 linhas na consola atrasa a leitura e pode provocar perdas).
 *
 * Regra do ficheiro: SÓ cresce pela ordem do líder, sem saltos.
 *   seq == último+1 -> escreve, e a seguir escreve os retidos que passaram a estar em sequência
 *   seq <= último   -> DUPLICADO (ignorado)
 *   seq >  último+1 -> EM ESPERA: guarda em memória (TreeMap ordenado por seq), NÃO escreve
 * Não há recuperação: se o registo em falta nunca chegar, o ficheiro pára nesse ponto e o
 * TreeMap cresce sem limite (e perde-se tudo se a réplica for reiniciada).
 */
public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;
    static final int BUFFER = 1000; // ponto 14: experimentar 10 -> datagrama truncado -> REJEITADO

    static RecordFile file;
    static long ultimo; // último seq ESCRITO no ficheiro
    static final TreeMap<Long, SensorRecord> retidos = new TreeMap<>();
    static boolean silencioso;

    // estatísticas desde o último resumo (uma "rajada")
    static int recebidos, escritos, duplicados, rejeitados;
    static long primeiroRx, ultimoRx;

    static void aplicar(SensorRecord r, String etiqueta) throws IOException {
        file.append(r);
        ultimo = r.getSeq();
        escritos++;
        if (!silencioso) System.out.println(etiqueta + ": " + r.toLine());
    }

    static void processar(String line) throws IOException {
        long agora = System.currentTimeMillis();
        if (recebidos == 0) primeiroRx = agora;
        ultimoRx = agora;
        recebidos++;

        SensorRecord r;
        try {
            r = SensorRecord.fromLine(line); // <- aqui a réplica "confia" que o que chegou é um registo;
        } catch (IllegalArgumentException e) { // NumberFormatException é subclasse: apanha tudo o que é inválido
            rejeitados++;
            if (!silencioso) System.out.println("REJEITADO: " + line);
            return; // a réplica NÃO termina: continua à escuta
        }

        long seq = r.getSeq();
        if (seq <= ultimo || retidos.containsKey(seq)) {
            duplicados++;
            if (!silencioso) System.out.println("DUPLICADO: " + seq);
        } else if (seq == ultimo + 1) {
            aplicar(r, "Aplicado");
            // cascata: escreve, por ordem, os retidos que passaram a estar em sequência
            while (!retidos.isEmpty() && retidos.firstKey() == ultimo + 1) {
                aplicar(retidos.pollFirstEntry().getValue(), "Aplicado (retido)");
            }
        } else {
            retidos.put(seq, r); // nunca se escreve fora de ordem
            if (!silencioso) System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
        }
    }

    static void resumo() {
        if (recebidos == 0) return;
        System.out.println("--- RESUMO ---");
        System.out.println("  recebidos=" + recebidos + " | escritos=" + escritos + " | duplicados=" + duplicados
                + " | rejeitados=" + rejeitados + " | retidos em memória=" + retidos.size());
        System.out.println("  1.º rx=" + primeiroRx + " ms | último rx=" + ultimoRx + " ms | duração="
                + (ultimoRx - primeiroRx) + " ms | último seq no ficheiro=" + ultimo);
        recebidos = escritos = duplicados = rejeitados = 0;
    }

    @SuppressWarnings("deprecation") // joinGroup(InetAddress) é deprecated desde o Java 14, mas funciona
    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        silencioso = args.length > 1 && args[1].equalsIgnoreCase("q");
        file = new RecordFile("replica-" + id + ".txt");
        try (MulticastSocket socket = new MulticastSocket(PORT)) { // várias réplicas podem fazer bind ao mesmo porto:
            // o MulticastSocket ativa SO_REUSEADDR, e o SO entrega uma cópia a cada socket do grupo
            ultimo = file.lastSeq(); // retoma de onde o ficheiro ficou (0 se for novo)
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group); // a partir daqui recebe o que for enviado ao grupo (e só a partir daqui)
            socket.setSoTimeout(2000); // 2 s sem receber nada = fim da rajada -> imprime o resumo
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT + " (último seq no ficheiro = " + ultimo + ")");
            byte[] buffer = new byte[BUFFER];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(p); // BLOQUEIA até chegar um datagrama (ou 2 s)
                } catch (SocketTimeoutException e) {
                    resumo();
                    continue;
                }
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                processar(line);
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}