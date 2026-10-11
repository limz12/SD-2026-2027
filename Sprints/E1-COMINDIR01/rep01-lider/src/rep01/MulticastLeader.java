package rep01;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket(); Scanner sc = new Scanner(System.in)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1);
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();
            System.out.println("Líder multicast pronto. <sensor> <temperatura>, rajada <n>, foraordem ou sair.");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break; // bloqueia à espera da CLI
                String input = sc.nextLine().trim();
                if (input.equalsIgnoreCase("sair")) break;
                String[] p = input.split("\\s+");
                if (p.length == 2 && p[0].equalsIgnoreCase("rajada")) {
                    try { seq = burst(Integer.parseInt(p[1]), seq, file, socket, group); }
                    catch (NumberFormatException e) { System.out.println("Número inválido."); }
                } else if (input.equalsIgnoreCase("foraordem")) {
                    seq = outOfOrder(seq, file, socket, group);
                } else if (p.length == 2) {
                    try { seq = sendOne(seq, p[0], Double.parseDouble(p[1]), file, socket, group); }
                    catch (NumberFormatException e) { System.out.println("Temperatura inválida."); }
                } else System.out.println("Formato inválido.");
            }
        } catch (IOException e) { System.out.println("IO: " + e.getMessage()); }
    }

    static long sendOne(long seq, String sensor, double temp, RecordFile file, MulticastSocket socket, InetAddress group) throws IOException {
        SensorRecord r = SensorRecord.now(++seq, sensor, temp);
        file.append(r); // primeiro a cópia local; só depois a propagação
        byte[] bytes = r.toLine().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, group, PORT));
        System.out.println("Registado e enviado: " + r.toLine());
        return seq;
    }

    static long burst(int n, long seq, RecordFile file, MulticastSocket socket, InetAddress group) throws IOException {
        if (n <= 0) { System.out.println("n deve ser positivo."); return seq; }
        long first = System.currentTimeMillis();
        for (int i = 0; i < n; i++)
            seq = sendOneSilently(seq, "SIM", ThreadLocalRandom.current().nextDouble(15, 30), file, socket, group);
        long last = System.currentTimeMillis();
        System.out.printf(Locale.ROOT, "RAJADA: %d registos; primeiro=%d, último=%d, duração=%d ms%n", n, first, last, last - first);
        return seq;
    }

    // Apenas para demonstrar o buffer da réplica: persiste 1, 2 e 3 mas transmite 2, 3, 1.
    static long outOfOrder(long seq, RecordFile file, MulticastSocket socket, InetAddress group) throws IOException {
        SensorRecord[] rs = new SensorRecord[3];
        for (int i = 0; i < 3; i++) { rs[i] = SensorRecord.now(++seq, "TESTE", 20 + i); file.append(rs[i]); }
        transmit(rs[1], socket, group); transmit(rs[2], socket, group); transmit(rs[0], socket, group);
        System.out.println("Teste fora de ordem enviado (2, 3, 1).");
        return seq;
    }

    private static long sendOneSilently(long seq, String sensor, double temp, RecordFile file, MulticastSocket socket, InetAddress group) throws IOException {
        SensorRecord r = SensorRecord.now(++seq, sensor, temp); file.append(r); transmit(r, socket, group); return seq;
    }
    private static void transmit(SensorRecord r, MulticastSocket s, InetAddress group) throws IOException {
        byte[] b = r.toLine().getBytes(StandardCharsets.UTF_8); s.send(new DatagramPacket(b, b.length, group, PORT));
    }
}
