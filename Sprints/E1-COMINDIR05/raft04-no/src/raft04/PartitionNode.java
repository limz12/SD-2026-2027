package raft04;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PartitionNode {
    private final int partition;
    private final int id;
    private final int dimensions;
    private final IpfsPubSub pubSub;
    private final Path recordsPath;
    private final Path eventsPath;
    private final Path heartbeatsPath;
    private final List<SensorRecord> records = new ArrayList<>();
    private final List<SensorRecord> pending = new ArrayList<>();
    private final VectorClock vector;
    private final RaftState raft;
    private ScheduledExecutorService leaderScheduler;
    private long seq;
    private long lamport;
    private long hb;
    private final Object eventLock = new Object();

    private PartitionNode(int partition, int id, int dimensions, String api) throws IOException {
        this.partition = partition;
        this.id = id;
        this.dimensions = dimensions;
        this.pubSub = new IpfsPubSub(api);
        this.recordsPath = Path.of("no-p" + partition + "-n" + id + ".txt");
        this.eventsPath = Path.of("eventos-p" + partition + "-n" + id + ".txt");
        this.heartbeatsPath = Path.of("hb-p" + partition + "-n" + id + ".txt");
        this.vector = new VectorClock(dimensions);
        readLocalRecords();
        this.raft = new RaftState(partition, id, Wire.GRUPO, Wire.ELEICAO_MIN_MS,
                Wire.ELEICAO_MAX_MS, Path.of("estado-p" + partition + "-n" + id + ".txt"), this::event);
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 4) {
            System.err.println("Uso: PartitionNode <k> <i> [N] [api]");
            return;
        }
        try {
            int partition = Integer.parseInt(args[0]);
            int id = Integer.parseInt(args[1]);
            int n = args.length >= 3 ? Integer.parseInt(args[2]) : 3;
            if (partition < 0 || partition >= n || id < 1 || id > Wire.GRUPO || n < 1) {
                throw new IllegalArgumentException("k, i ou N fora dos limites");
            }
            String api = args.length == 4 ? args[3] : "http://127.0.0.1:5001/api/v0";
            new PartitionNode(partition, id, n, api).start();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Não foi possível iniciar PartitionNode: " + e.getMessage());
        }
    }

    private void start() {
        event("ARRANQUE;p" + partition + ";no=n" + id + ";termo=" + raft.term());
        Thread server = new Thread(this::serveRecovery, "tcp-p" + partition + "-n" + id);
        server.start();
        subscribe(Wire.topicIn(partition), this::onInput, "entrada");
        subscribe(Wire.topicData(partition), this::onBatchMessage, "lotes");
        subscribe(Wire.topicElection(partition), this::onElectionMessage, "eleicao");
        Thread election = new Thread(this::checkElection, "eleicao-p" + partition + "-n" + id);
        election.setDaemon(true);
        election.start();
        System.out.println("PartitionNode p" + partition + "/n" + id + " ativo, TCP "
                + Wire.nodePort(partition, id) + ", termo " + raft.term());
    }

    private void readLocalRecords() throws IOException {
        if (!Files.exists(recordsPath)) return;
        long expected = 1;
        for (String line : Files.readAllLines(recordsPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            SensorRecord record = SensorRecord.fromLine(line, dimensions);
            if (record.getSeq() != expected++) {
                throw new IOException("Sequência inválida em " + recordsPath + ": " + line);
            }
            records.add(record);
        }
        if (!records.isEmpty()) {
            SensorRecord last = records.get(records.size() - 1);
            seq = last.getSeq();
            lamport = last.getLamport();
            vector.merge(last.getVector());
        } else {
            lamport = 0;
            vector.set(new VectorClock(dimensions));
        }
    }

    private void subscribe(String topic, java.util.function.Consumer<String> handler, String label) {
        Thread thread = new Thread(() -> {
            try {
                pubSub.subscribe(topic, message -> {
                    try {
                        handler.accept(message);
                    } catch (RuntimeException e) {
                        event("ERRO;p" + partition + ";canal=" + label + ";detalhe=" + e.getMessage());
                    }
                });
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                event("ERRO;p" + partition + ";subscricao=" + label + ";detalhe=" + e.getMessage());
            }
        }, "sub-" + label + "-p" + partition + "-n" + id);
        thread.setDaemon(true);
        thread.start();
    }

    private void checkElection() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(Wire.VERIFICACAO_MS);
                long electionTerm = raft.tick();
                if (electionTerm < 0) continue;
                long lastSeq;
                long lastTerm;
                synchronized (this) {
                    lastSeq = seq;
                    lastTerm = records.isEmpty() ? 0 : records.get(records.size() - 1).getTerm();
                }
                publishElection(new VoteRequest(electionTerm, id, lastSeq, lastTerm).encode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                event("ERRO;p" + partition + ";eleicao;detalhe=" + e.getMessage());
            }
        }
    }

    private void onInput(String message) {
        if (!"IN".equals(Wire.type(message))) return;
        String[] fields = message.trim().split(";", -1);
        if (fields.length != 5) throw new IllegalArgumentException("IN inválido: " + message);
        String sensor = fields[1];
        if (new Partitioner(dimensions).of(sensor) != partition) {
            event("IGNORADO;p" + partition + ";sensor_fora_da_particao=" + sensor);
            return;
        }
        double temperature = Double.parseDouble(fields[2]);
        long incomingLamport = Long.parseLong(fields[3]);
        VectorClock incoming = VectorClock.parse(fields[4], dimensions);
        SensorRecord record;
        synchronized (this) {
            long term = raft.termIfLeader();
            if (term < 0) {
                event("IGNORADO;p" + partition + ";IN sem lideranca;termo=" + raft.term());
                return;
            }
            long nextSeq = seq + 1;
            VectorClock nextVector = vector.copy();
            nextVector.merge(incoming);
            nextVector.tick(partition);
            long nextLamport = Math.max(lamport, incomingLamport) + 1;
            record = SensorRecord.now(nextSeq, term, sensor, temperature, nextLamport, nextVector);
            appendRecord(record);
            seq = nextSeq;
            lamport = nextLamport;
            vector.set(nextVector);
            records.add(record);
            pending.add(record);
            event("RECEBIDO;p" + partition + ";seq=" + seq + ";termo=" + term + ";sensor=" + sensor);
        }
    }

    private void onElectionMessage(String message) {
        if (Wire.isolated(partition, id)) return;
        String type = Wire.type(message);
        if ("PEDIDO_VOTO".equals(type)) {
            VoteRequest request = VoteRequest.decode(message);
            if (request.candidate == id) return;
            long myLastSeq;
            long myLastTerm;
            synchronized (this) {
                myLastSeq = seq;
                myLastTerm = records.isEmpty() ? 0 : records.get(records.size() - 1).getTerm();
            }
            RaftState.Vote vote = raft.onVoteRequest(request.term, request.candidate,
                    request.lastSeq, request.lastTerm, myLastSeq, myLastTerm);
            try {
                publishElection(new VoteReply(vote.term, id, request.candidate, vote.granted).encode());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                event("ERRO;p" + partition + ";publicar_VOTO;detalhe=" + e.getMessage());
            }
            if (vote.granted) event("VOTO;p" + partition + ";termo=" + vote.term + ";para=n" + request.candidate);
        } else if ("VOTO".equals(type)) {
            VoteReply reply = VoteReply.decode(message);
            if (reply.voter == id || reply.candidate != id) return;
            if (raft.onVote(reply.term, reply.voter, reply.candidate, reply.granted)) becomeLeader();
        }
    }

    private void onBatchMessage(String message) {
        if (Wire.isolated(partition, id)) return;
        Batch batch = Batch.decode(message, dimensions);
        if (batch.leader == id) return;
        if (!raft.onBatch(batch.term, batch.leader)) {
            event("IGNORADO;p" + partition + ";lote_obsoleto=" + batch.term + ";termo=" + raft.term());
            return;
        }
        event("RECEBIDO;p" + partition + ";LOTE;termo=" + batch.term + ";lider=n" + batch.leader
                + ";hb=" + batch.hb + ";n=" + batch.records.size());
        synchronized (this) {
            boolean diverged = false;
            for (SensorRecord incoming : batch.records) {
                if (incoming.getSeq() <= seq) {
                    SensorRecord local = records.get((int) incoming.getSeq() - 1);
                    if (local.getTerm() != incoming.getTerm()) diverged = true;
                } else if (incoming.getSeq() != seq + 1) {
                    diverged = true;
                } else {
                    appendRecord(incoming);
                    records.add(incoming);
                    seq = incoming.getSeq();
                    lamport = incoming.getLamport();
                    vector.merge(incoming.getVector());
                }
            }
            long localTerm = records.isEmpty() ? 0 : records.get(records.size() - 1).getTerm();
            if (seq > batch.lastSeq || (seq == batch.lastSeq && localTerm != batch.lastTerm)) diverged = true;
            if (seq < batch.lastSeq) diverged = true;
            if (diverged) reconcile(batch);
        }
    }

    private void becomeLeader() {
        synchronized (this) {
            if (raft.termIfLeader() < 0) return;
            if (leaderScheduler != null) leaderScheduler.shutdownNow();
            pending.clear();
            hb = 0;
            if (!records.isEmpty()) {
                SensorRecord last = records.get(records.size() - 1);
                seq = last.getSeq();
                lamport = last.getLamport();
                vector.merge(last.getVector());
            }
            leaderScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "lotes-p" + partition + "-n" + id);
                thread.setDaemon(true);
                return thread;
            });
            leaderScheduler.scheduleAtFixedRate(this::sendBatchSafely, 0,
                    Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void sendBatchSafely() {
        try {
            sendBatch();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            event("ERRO;p" + partition + ";publicar_LOTE;detalhe=" + e.getMessage());
        }
    }

    private void sendBatch() throws IOException, InterruptedException {
        Batch batch;
        synchronized (this) {
            long term = raft.termIfLeader();
            if (term < 0) {
                if (leaderScheduler != null) leaderScheduler.shutdown();
                return;
            }
            List<SensorRecord> outgoing = new ArrayList<>(pending);
            pending.clear();
            long lastTerm = records.isEmpty() ? 0 : records.get(records.size() - 1).getTerm();
            batch = new Batch(term, id, "127.0.0.1", Wire.nodePort(partition, id),
                    ++hb, seq, lastTerm, outgoing);
        }
        if (Wire.isolated(partition, id)) {
            event("ISOLADO;p" + partition + ";nao_publicado;termo=" + batch.term);
            return;
        }
        String encoded = batch.encode();
        pubSub.publish(Wire.topicData(partition), encoded);
        String line = System.currentTimeMillis() + ";" + batch.term + ";" + batch.hb + ";"
                + System.currentTimeMillis() + ";" + batch.lastSeq + ";" + batch.lastTerm + ";"
                + batch.records.size() + ";" + encoded.getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(heartbeatsPath, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void publishElection(String message) throws IOException, InterruptedException {
        if (Wire.isolated(partition, id)) {
            event("ISOLADO;p" + partition + ";eleicao_nao_publicada");
            return;
        }
        pubSub.publish(Wire.topicElection(partition), message);
    }

    private void appendRecord(SensorRecord record) {
        try {
            Files.writeString(recordsPath, record.toLine() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Não foi possível acrescentar " + recordsPath, e);
        }
    }

    private void serveRecovery() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(Wire.nodePort(partition, id)));
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> serveRequest(socket), "recuperacao-p" + partition + "-n" + id);
                worker.setDaemon(true);
                worker.start();
            }
        } catch (IOException e) {
            event("ERRO;p" + partition + ";servidor_TCP;detalhe=" + e.getMessage());
        }
    }

    private void serveRequest(Socket socket) {
        try (Socket client = socket;
                BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
            client.setSoTimeout(Wire.T_TCP_MS);
            if (Wire.isolated(partition, id)) throw new IOException("nó isolado");
            String request = input.readLine();
            String[] fields = request == null ? new String[0] : request.split(";", -1);
            if (fields.length != 2 || !"PEDIDO".equals(fields[0])) throw new IOException("pedido TCP inválido");
            long from = Long.parseLong(fields[1]);
            synchronized (this) {
                for (SensorRecord record : records) {
                    if (record.getSeq() >= from) {
                        output.write(record.toLine());
                        output.newLine();
                    }
                }
            }
            output.write("FIM");
            output.newLine();
            output.flush();
        } catch (IOException | NumberFormatException e) {
            event("ERRO;p" + partition + ";TCP_servidor;detalhe=" + e.getMessage());
        }
    }

    private List<SensorRecord> fetch(String address, int port, long from) throws IOException {
        if (Wire.isolated(partition, id)) throw new IOException("nó isolado: recuperação TCP indisponível");
        List<SensorRecord> received = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), Wire.T_TCP_MS);
            socket.setSoTimeout(Wire.T_TCP_MS);
            try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                output.write("PEDIDO;" + from);
                output.newLine();
                output.flush();
                String line;
                while ((line = input.readLine()) != null && !"FIM".equals(line)) {
                    received.add(SensorRecord.fromLine(line, dimensions));
                }
                if (line == null) throw new IOException("resposta TCP sem FIM");
            }
        }
        return received;
    }

    private void reconcile(Batch batch) {
        try {
            long start = records.isEmpty() ? 1 : startOfLastTermBlock();
            List<SensorRecord> leaderRecords = fetch(batch.address, batch.port, start);
            while (start > 1 && !sameAtSequence(records, leaderRecords, start)) {
                long previous = start - 1;
                long previousTerm = records.get((int) previous - 1).getTerm();
                while (previous > 1 && records.get((int) previous - 2).getTerm() == previousTerm) previous--;
                start = previous;
                leaderRecords = fetch(batch.address, batch.port, start);
            }

            Map<Long, SensorRecord> remote = new HashMap<>();
            for (SensorRecord record : leaderRecords) remote.put(record.getSeq(), record);
            long remoteLast = leaderRecords.isEmpty() ? start - 1 : leaderRecords.get(leaderRecords.size() - 1).getSeq();
            long localLast = records.isEmpty() ? 0 : records.get(records.size() - 1).getSeq();
            long firstDifference = -1;
            for (long current = start; current <= Math.max(localLast, remoteLast); current++) {
                SensorRecord local = current <= localLast ? records.get((int) current - 1) : null;
                SensorRecord leader = remote.get(current);
                if (local == null || leader == null || local.getTerm() != leader.getTerm()) {
                    firstDifference = current;
                    break;
                }
            }
            if (firstDifference < 0) return;

            List<SensorRecord> replacement = new ArrayList<>();
            for (SensorRecord record : records) {
                if (record.getSeq() < firstDifference) replacement.add(record);
            }
            int kept = replacement.size();
            for (SensorRecord record : leaderRecords) {
                if (record.getSeq() >= firstDifference) replacement.add(record);
            }
            int discarded = records.size() - kept;
            Set<Long> terms = new LinkedHashSet<>();
            List<String> discardedData = new ArrayList<>();
            for (SensorRecord record : records) {
                if (record.getSeq() >= firstDifference) {
                    terms.add(record.getTerm());
                    discardedData.add(record.getSeq() + ":" + record.getTerm() + ":"
                            + record.getSensorId() + ":" + record.getTemperature());
                }
            }
            writeAtomically(replacement);
            records.clear();
            records.addAll(replacement);
            seq = records.isEmpty() ? 0 : records.get(records.size() - 1).getSeq();
            vector.set(new VectorClock(dimensions));
            if (!records.isEmpty()) {
                SensorRecord last = records.get(records.size() - 1);
                lamport = last.getLamport();
                vector.merge(last.getVector());
            } else {
                lamport = 0;
            }
            if (discarded > 0) {
                event("TRUNCADO;p" + partition + ";de=" + firstDifference + ";descartados=" + discarded
                        + ";termos=" + terms + ";registos=" + String.join(",", discardedData));
            } else if (seq > 0 && seq == batch.lastSeq) {
                event("RECUPERACAO;p" + partition + ";de=" + firstDifference + ";ate=" + seq);
            }
        } catch (IOException | RuntimeException e) {
            event("ERRO;p" + partition + ";RECUPERACAO;detalhe=" + e.getMessage());
        }
    }

    private long startOfLastTermBlock() {
        int index = records.size() - 1;
        long term = records.get(index).getTerm();
        while (index > 0 && records.get(index - 1).getTerm() == term) index--;
        return records.get(index).getSeq();
    }

    private static boolean sameAtSequence(List<SensorRecord> local, List<SensorRecord> remote, long sequence) {
        SensorRecord a = local.get((int) sequence - 1);
        for (SensorRecord b : remote) {
            if (b.getSeq() == sequence) return a.getTerm() == b.getTerm();
        }
        return false;
    }

    private void writeAtomically(List<SensorRecord> replacement) throws IOException {
        Path tmp = recordsPath.resolveSibling(recordsPath.getFileName() + ".tmp");
        StringBuilder content = new StringBuilder();
        for (SensorRecord record : replacement) content.append(record.toLine()).append('\n');
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, recordsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void event(String message) {
        String line = System.currentTimeMillis() + ";" + message;
        synchronized (eventLock) {
            try {
                Files.writeString(eventsPath, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("ERRO ao escrever " + eventsPath + ": " + e.getMessage());
            }
        }
        System.out.println(line);
    }
}
