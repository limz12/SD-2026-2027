package rep04;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Réplica de várias partições, com recuperação por TCP e DETETOR DE FALHAS do
 * líder.
 *
 * Uso: java [-Dsilencioso=true] [-Dtempos=true] [-Dperda=
 * <p>
 * ] [-Ddescartar=<hb>] [-Drep04.periodo=<ms>]
 * [-Drep04.fator=<n>] -cp out rep04.PartitionReplica <id> <partições> <N>
 * [portoApi=5001]
 *
 * Por partição k: um ESTADO (último seq escrito, retidos, endereço do líder,
 * ficheiro), um
 * HeartbeatMonitor e uma THREAD de subscrição bloqueada à espera de cada LOTE.
 * Uma thread "verificacao"
 * chama check() em todos os monitores a cada VERIFICACAO_MS.
 *
 * Por cada LOTE (que é também o heartbeat do líder):
 * 1. monitor.onHeartbeat(inc, hb) -> pode assinalar REINICIO, HB_PERDIDO,
 * RETOMA
 * 2. evento RECEBIDO (inc, hb, ultimoSeq, n, intervalo de seq)
 * 3. aplica os registos pela ordem, com a regra da COMINDIR02 (duplicado
 * ignorado; lacuna > LIMITE_RETIDOS
 * resolvida por TCP)
 * 4. se o último seq ESCRITO < ultimoSeq do cabeçalho, faltam registos: pede-os
 * por TCP ao endereço e
 * porto do cabeçalho (evento RECUPERACAO). É assim que se deteta a perda do
 * ÚLTIMO registo.
 *
 * Um participante que suspeita do líder limita-se a ASSINALÁ-LO: não altera a
 * aplicação de registos
 * (nem descarta registos de um LOTE de uma incarnação antiga: são registos
 * válidos, deduplicados por seq).
 * A réplica continua a NÃO consultar os carimbos L e V (sem entrega causal).
 *
 * Simulação (só nos LOTES vindos do PubSub): -Dperda=
 * <p>
 * descarta cada LOTE inteiro com probabilidade p;
 * -Ddescartar=<hb> descarta a primeira receção do lote com esse número de
 * heartbeat (em cada partição).
 * -Dtempos=true escreve "ESCRITA t=<ms> p<k> seq=<seq> <sensor> L=<L> V=<V>"
 * por registo escrito.
 */
public class PartitionReplica {
    static final boolean SILENCIOSO = Boolean.getBoolean("silencioso");
    static final boolean TEMPOS = Boolean.getBoolean("tempos");
    static final double PERDA = Double.parseDouble(System.getProperty("perda", "0"));
    static final long DESCARTAR = Long.parseLong(System.getProperty("descartar", "-1"));
    static final Random RND = new Random();
    static String idReplica = "1";

    static void dizer(String s) {
        System.out.println(s);
    }

    // Eventos do detetor e da réplica: console + monitor-replica<id>.txt,
    // "instante;evento;p<k>;detalhe".
    // synchronized: é chamada pelas threads de subscrição e pela de verificação
    // (via monitor.log).
    static synchronized void evento(String linha) {
        String l = System.currentTimeMillis() + ";" + linha;
        try {
            Files.writeString(Paths.get("monitor-replica" + idReplica + ".txt"), l + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            dizer("ERRO a escrever o ficheiro de eventos: " + e.getMessage());
        }
        if (!linha.startsWith("RECEBIDO") && !linha.startsWith("RECUPERACAO"))
            dizer("[monitor] " + l);
    }

    static class Estado {
        final int k;
        final RecordFile file;
        final HeartbeatMonitor monitor;
        long ultimo; // último seq ESCRITO no ficheiro
        final TreeMap<Long, SensorRecord> retidos = new TreeMap<>();
        String lider; // endereço e porto do líder: aprendidos
        int portoLider = -1; // com CADA lote recebido
        boolean descartarPendente = DESCARTAR >= 1;

        Estado(int k, RecordFile file) {
            this.k = k;
            this.file = file;
            this.monitor = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, PartitionReplica::evento);
        }

        void falar(String s) {
            if (!SILENCIOSO)
                dizer("[p" + k + "] " + s);
        }

        void sempre(String s) {
            dizer("[p" + k + "] " + s);
        }

        // thread de subscrição desta partição, uma vez por LOTE
        synchronized void onLote(String msg) {
            Batch b;
            try {
                b = Batch.decode(msg); // valida o cabeçalho e que o nº de linhas coincide com n
            } catch (IllegalArgumentException e) {
                sempre("REJEITADO: " + msg.split("\n", 2)[0] + " (" + e.getMessage() + ")");
                return;
            }
            if (descartarPendente && b.hb == DESCARTAR) {
                descartarPendente = false;
                sempre("DESCARTADO (simulação): lote hb=" + b.hb);
                return;
            }
            if (PERDA > 0 && RND.nextDouble() < PERDA) {
                sempre("DESCARTADO (simulação): lote hb=" + b.hb);
                return;
            }

            monitor.onHeartbeat(b.inc, b.hb);
            String faixa = b.records.isEmpty() ? "-"
                    : b.records.get(0).getSeq() + ".." + b.records.get(b.records.size() - 1).getSeq();
            evento("RECEBIDO;p" + k + ";inc=" + b.inc + ",hb=" + b.hb + ",ultimoSeq=" + b.lastSeq
                    + ",n=" + b.records.size() + ",seq=" + faixa);

            // o endereço do líder é aprendido AQUI, em cada lote: se o líder for reiniciado
            // noutra máquina,
            // a réplica passa a usar o endereço novo logo no lote seguinte.
            lider = b.address;
            portoLider = b.port;

            for (SensorRecord r : b.records) {
                aplicar(r, false);
                if (retidos.size() > Wire.LIMITE_RETIDOS) {
                    recuperar(ultimo + 1, retidos.firstKey() - 1); // regra da COMINDIR02
                }
            }
            if (ultimo < b.lastSeq) {
                // faltam registos até ao ultimoSeq do cabeçalho (perda de lote, ou do último
                // registo da série).
                // Se a recuperação falhar, a condição mantém-se e é tentada de novo no lote
                // seguinte (≤ Δ).
                recuperar(ultimo + 1, b.lastSeq);
            }
            sempre("LOTE hb=" + b.hb + " n=" + b.records.size() + " seq=" + faixa + " ultimoSeq=" + b.lastSeq
                    + " | ficheiro até " + ultimo + " | retidos " + retidos.size());
        }

        void aplicar(SensorRecord r, boolean viaTcp) {
            long seq = r.getSeq();
            if (seq <= ultimo || retidos.containsKey(seq)) {
                falar("DUPLICADO: " + seq);
            } else if (seq == ultimo + 1) {
                escrever(r, viaTcp ? "RECUPERADO" : "Aplicado");
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
            if (TEMPOS) {
                dizer("ESCRITA t=" + System.currentTimeMillis() + " p" + k + " seq=" + r.getSeq() + " "
                        + r.getSensorId() + " L=" + r.getLamport() + " V=" + r.getVector());
            }
            falar(etiqueta + ": " + r.getSeq());
        }

        // Corre NA PRÓPRIA thread de subscrição: enquanto dura, esta partição deixa de
        // ler a resposta do
        // pedido pubsub/sub (as mensagens seguintes ficam à espera nos buffers da
        // ligação HTTP e do daemon).
        void recuperar(long de, long ate) {
            if (lider == null || ate < de)
                return;
            evento("RECUPERACAO;p" + k + ";de=" + de + ";ate=" + ate);
            sempre("RECUPERAÇÃO: " + de + ".." + ate + " pedida a " + lider + ":" + portoLider);
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
                    aplicar(SensorRecord.fromLine(linha), true); // MESMA regra que os registos do lote
                }
                if (!fim) {
                    throw new IOException("resposta interrompida antes de FIM");
                }
            } catch (IOException | IllegalArgumentException e) {
                sempre("RECUPERAÇÃO FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    static void subscrever(IpfsPubSub ps, Estado e) {
        try {
            ps.subscribe(Wire.topicData(e.k), msg -> { // BLOQUEIA aqui: a resposta HTTP nunca termina
                try {
                    e.onLote(msg);
                } catch (UncheckedIOException ex) {
                    dizer("[p" + e.k + "] ERRO A ESCREVER NO FICHEIRO: " + ex.getCause().getMessage());
                    System.exit(1);
                }
            });
            dizer("[p" + e.k + "] subscrição terminada");
        } catch (IOException ex) {
            dizer("[p" + e.k + "] IO: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Uso: PartitionReplica <id> <partições> <N> [portoApi]   ex.: PartitionReplica 1 0,1 3");
            return;
        }
        idReplica = args[0];
        int n;
        try {
            n = Integer.parseInt(args[2]);
            if (n <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.out.println("Número de partições inválido: " + args[2]);
            return;
        }
        String api = "http://127.0.0.1:" + (args.length > 3 ? args[3] : "5001") + "/api/v0";
        IpfsPubSub ps = new IpfsPubSub(api);

        List<Estado> estados = new ArrayList<>();
        try {
            for (String s : new LinkedHashSet<>(Arrays.asList(args[1].split(",")))) {
                int k = Integer.parseInt(s.trim());
                Estado e = new Estado(k, new RecordFile("replica-" + idReplica + "-p" + k + ".txt", n));
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

        StringBuilder sb = new StringBuilder("Réplica " + idReplica + " (API " + api + ") guarda:");
        for (Estado e : estados) {
            sb.append(" p").append(e.k).append("(último seq=").append(e.ultimo).append(")");
        }
        sb.append(" | Δ=").append(Wire.PERIODO_HB_MS).append(" ms, timeout=")
                .append(Wire.PERIODO_HB_MS * Wire.FATOR_TIMEOUT).append(" ms");
        if (PERDA > 0)
            sb.append(" | perda simulada de lotes=").append(PERDA);
        if (DESCARTAR >= 1)
            sb.append(" | descartar lote hb=").append(DESCARTAR);
        dizer(sb.toString());

        for (Estado e : estados) {
            new Thread(() -> subscrever(ps, e), "sub-p" + e.k).start(); // uma thread por partição
        }

        // thread de verificação: o detetor avalia o TIMEOUT a cada VERIFICACAO_MS
        Thread verificacao = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Wire.VERIFICACAO_MS);
                } catch (InterruptedException ex) {
                    return;
                }
                for (Estado e : estados)
                    e.monitor.check();
            }
        }, "verificacao");
        verificacao.setDaemon(true);
        verificacao.start();
    }
}