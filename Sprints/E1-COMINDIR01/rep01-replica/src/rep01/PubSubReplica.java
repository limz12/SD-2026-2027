package rep01;

import java.io.IOException;

public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";
    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5001;
        try {
            ReplicaState state = new ReplicaState(new RecordFile("replica-" + id + ".txt"));
            IpfsPubSub ps = new IpfsPubSub("http://127.0.0.1:" + port + "/api/v0");
            System.out.println("Réplica " + id + " subscrita em " + TOPIC + " (API " + port + ")");
            ps.subscribe(TOPIC, text -> { state.accept(text); state.printStats(); }); // bloqueia enquanto a subscrição estiver aberta
        } catch (IOException e) { System.out.println("IO: " + e.getMessage()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
