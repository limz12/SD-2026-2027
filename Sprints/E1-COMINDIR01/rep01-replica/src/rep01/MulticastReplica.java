package rep01;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;
    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            ReplicaState state = new ReplicaState(file);
            socket.joinGroup(InetAddress.getByName(GROUP));
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT);
            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // bloqueia até receber um datagrama
                state.accept(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                state.printStats();
            }
        } catch (IOException e) { System.out.println("IO: " + e.getMessage()); }
    }
}
