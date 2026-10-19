package rep02;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Réplica de várias partições, com recuperação por TCP.
 *
 * Uso: java [-Dperda=
 * <p>
 * ] [-Ddescartar=<seq>] [-Dsilencioso=true] -cp out rep02.PartitionReplica
 * <id> <partições> [portoApi=5001] ex.: PartitionReplica 1 0,1
 *
 * Por partição k existe: um ESTADO próprio (último seq escrito, retidos,
 * endereço do líder, ficheiro
 * replica-<id>-p<k>.txt) e uma THREAD própria, bloqueada em subscribe() à
 * espera de cada REG.
 * Entre threads de partições diferentes não há estado partilhado a proteger:
 * cada Estado só é
 * tocado pela sua thread (e, de leitura, pelo monitor, por isso os seus métodos
 * são synchronized).
 * O que é comum é só a consola e o HttpClient, ambos seguros para vários
 * threads.
 * Consequência: uma recuperação na p0 NÃO bloqueia a receção da p1.
 *
 * Regra de aplicação (por partição):
 * seq == último+1 -> escreve, e depois os retidos que passaram a estar em
 * sequência
 * seq <= último / retido -> DUPLICADO (ignora)
 * seq > último+1 -> retém em memória; se retidos > LIMITE_RETIDOS, RECUPERA por
 * TCP
 *
 * Simulação de perdas (só nas mensagens vindas do PubSub, nunca nas recuperadas
 * por TCP):
 * -Dperda=
 * <p>
 * descarta cada REG com probabilidade p
 * -Ddescartar=<seq> descarta apenas a PRIMEIRA receção do registo com esse
 * número (em cada partição)
 */
public class PartitionReplica {
    static final boolean SILENCIOSO = Boolean.getBoolean("silencioso");
    static final double PERDA = Double.parseDouble(System.getProperty("perda", "0"));
    static final long DESCARTAR = Long.parseLong(System.getProperty("descartar", "-1"));
    static final Random RND = new Random();

    static void dizer(String s) {
        System.out.println(s);
    }

    static class Estado {
        final int k;
        final RecordFile file;
        long ultimo; // último seq ESCRITO no ficheiro
        final TreeMap<Long, SensorRecord> retidos = new TreeMap<>();
        String lider; // endereço e porto do líder: aprendidos
        int portoLider = -1; // com CADA mensagem REG recebida
        boolean descartarPendente = DESCARTAR >= 1; // só a primeira receção

        // estatísticas desde o último resumo
        int recebidos, escritos, duplicados, descartados, rejeitados, ligacoes, porTcp, falhas;
        long primeiroRx, ultimoRx, ultimaEscrita;

        Estado(int k, RecordFile file) {
            this.k = k;
            this.file = file;
        }

        void falar(String s) {
            if (!SILENCIOSO)
                dizer("[p" + k + "] " + s);
        }

        void sempre(String s) {
            dizer("[p" + k + "] " + s);
        }

        // chamado pela thread de subscrição desta partição, uma vez por mensagem do
        // PubSub
        synchronized void onMensagem(String msg) {
            long agora = System.currentTimeMillis();
            if (recebidos == 0)
                primeiroRx = agora;
            ultimoRx = agora;
            recebidos++;

            String[] partes;
            SensorRecord r;
            int porto;
            try {
                partes = Wire.regParts(msg);
                porto = Integer.parseInt(partes[1]);
                r = SensorRecord.fromLine(partes[2]);
            } catch (IllegalArgumentException e) { // inclui NumberFormatException
                rejeitados++;
                sempre("REJEITADO: " + msg);
                return;
            }

            long seq = r.getSeq();
            if (descartarPendente && seq == DESCARTAR) {
                descartarPendente = false;
                descartados++;
                falar("DESCARTADO (simulação): " + seq);
                return;
            }
            if (PERDA > 0 && RND.nextDouble() < PERDA) {
                descartados++;
                falar("DESCARTADO (simulação): " + seq);
                return;
            }

            // o endereço do líder é aprendido AQUI, em cada mensagem recebida: se o líder
            // for reiniciado
            // noutra máquina, a réplica passa a usar o endereço novo logo na mensagem
            // seguinte.
            lider = partes[0];
            portoLider = porto;

            aplicar(r, false);

            // A condição continua a verificar-se enquanto houver mais de LIMITE_RETIDOS
            // retidos, por
            // isso uma recuperação falhada é tentada de novo na receção do registo
            // seguinte.
            if (retidos.size() > Wire.LIMITE_RETIDOS) {
                recuperar();
            }
        }

        void aplicar(SensorRecord r, boolean viaTcp) {
            long seq = r.getSeq();
            if (seq <= ultimo || retidos.containsKey(seq)) {
                duplicados++;
                falar("DUPLICADO: " + seq);
            } else if (seq == ultimo + 1) {
                escrever(r, viaTcp ? "RECUPERADO" : "Aplicado");
                // cascata: escreve, por ordem, os retidos que passaram a estar em sequência
                while (!retidos.isEmpty() && retidos.firstKey() == ultimo + 1) {
                    escrever(retidos.pollFirstEntry().getValue(), "Aplicado (retido)");
                }
            } else {
                retidos.put(seq, r); // nunca se escreve fora de ordem
                falar("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
            }
        }

        void escrever(SensorRecord r, String etiqueta) {
            try {
                file.append(r);
            } catch (IOException e) {
                throw new UncheckedIOException(e); // não pode ser confundido com uma falha de rede
            }
            ultimo = r.getSeq();
            escritos++;
            ultimaEscrita = System.currentTimeMillis();
            falar(etiqueta + ": " + r.getSeq());
        }

        // Corre NA PRÓPRIA thread de subscrição: enquanto dura, esta partição deixa de
        // ler a resposta
        // do pedido pubsub/sub. As mensagens que o daemon recebe entretanto ficam à
        // espera nos buffers
        // da ligação HTTP e do daemon (e podem perder-se se estes encherem).
        void recuperar() {
            long de = ultimo + 1;
            long ate = retidos.firstKey() - 1; // tudo o que falta ANTES do menor registo retido
            sempre("RECUPERAÇÃO: " + de + ".." + ate + " pedida a " + lider + ":" + portoLider);
            ligacoes++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(lider, portoLider), Wire.T_TCP_MS); // BLOQUEIA até ser aceite
                s.setSoTimeout(Wire.T_TCP_MS); // readLine deixa de bloquear para sempre
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8),
                        true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out.println("PEDIDO " + de + " " + ate);

                boolean fim = false;
                String linha;
                while ((linha = in.readLine()) != null) { // BLOQUEIA à espera de cada linha
                    if (linha.equals("FIM")) {
                        fim = true;
                        break;
                    }
                    if (linha.startsWith("ERRO")) {
                        throw new IOException("o líder respondeu: " + linha);
                    }
                    SensorRecord r = SensorRecord.fromLine(linha);
                    porTcp++;
                    aplicar(r, true); // MESMA regra que as mensagens do PubSub; escreve também os retidos seguintes
                }
                if (!fim) {
                    throw new IOException("resposta interrompida antes de FIM");
                }
            } catch (IOException | IllegalArgumentException e) {
                // O que já foi escrito mantém-se, os retidos continuam em memória, e a condição
                // (retidos > LIMITE) continua verdadeira: tenta-se de novo na mensagem
                // seguinte.
                falhas++;
                sempre("RECUPERAÇÃO FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // 2 s sem receber nada depois de ter recebido algo = fim da rajada -> resumo
        synchronized void resumoSeInativa() {
            if (recebidos == 0 || System.currentTimeMillis() - ultimoRx < 2000)
                return;
            sempre("--- RESUMO ---");
            dizer("  recebidos=" + recebidos + " | escritos=" + escritos + " | duplicados=" + duplicados
                    + " | descartados(sim.)=" + descartados + " | rejeitados=" + rejeitados);
            dizer("  recuperação: ligações=" + ligacoes + " | registos por TCP=" + porTcp + " | falhadas=" + falhas);
            dizer("  retidos em memória=" + retidos.size() + " | último seq no ficheiro=" + ultimo
                    + " | 1.º rx=" + primeiroRx + " ms | último rx=" + ultimoRx + " ms | última escrita="
                    + ultimaEscrita + " ms");
            recebidos = escritos = duplicados = descartados = rejeitados = ligacoes = porTcp = falhas = 0;
        }
    }

    static void subscrever(IpfsPubSub ps, Estado e) {
        try {
            ps.subscribe(Wire.topicData(e.k), msg -> { // BLOQUEIA aqui: a resposta HTTP nunca termina
                try {
                    e.onMensagem(msg);
                } catch (UncheckedIOException ex) {
                    dizer("[p" + e.k + "] ERRO A ESCREVER NO FICHEIRO: " + ex.getCause().getMessage());
                    System.exit(1);
                }
            });
            dizer("[p" + e.k + "] subscrição terminada");
        } catch (IOException ex) {
            // Daemon parado: ConnectException. Pubsub desativado no nó: "sub falhou (500)".
            dizer("[p" + e.k + "] IO: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionReplica <id> <partições> [portoApi]   ex.: PartitionReplica 1 0,1");
            return;
        }
        String id = args[0];
        String api = "http://127.0.0.1:" + (args.length > 2 ? args[2] : "5001") + "/api/v0";
        IpfsPubSub ps = new IpfsPubSub(api);

        List<Estado> estados = new ArrayList<>();
        try {
            for (String s : new LinkedHashSet<>(Arrays.asList(args[1].split(",")))) {
                int k = Integer.parseInt(s.trim());
                Estado e = new Estado(k, new RecordFile("replica-" + id + "-p" + k + ".txt"));
                e.ultimo = e.file.lastSeq(); // retoma de onde o ficheiro ficou (0 se for novo)
                estados.add(e);
            }
        } catch (NumberFormatException e) {
            System.out.println("Lista de partições inválida: " + args[1]);
            return;
        } catch (IOException e) {
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        StringBuilder sb = new StringBuilder("Réplica " + id + " (API " + api + ") guarda:");
        for (Estado e : estados) {
            sb.append(" p").append(e.k).append("(último seq=").append(e.ultimo).append(")");
        }
        if (PERDA > 0)
            sb.append(" | perda simulada=").append(PERDA);
        if (DESCARTAR >= 1)
            sb.append(" | descartar seq=").append(DESCARTAR);
        dizer(sb.toString());

        for (Estado e : estados) {
            new Thread(() -> subscrever(ps, e), "sub-p" + e.k).start(); // uma thread por partição
        }

        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    return;
                }
                for (Estado e : estados)
                    e.resumoSeInativa();
            }
        }, "monitor");
        monitor.setDaemon(true);
        monitor.start();
    }
}