package rep01;

import java.io.IOException;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";
    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        RecordFile file = new RecordFile("lider.txt");
        try (Scanner sc = new Scanner(System.in)) {
            IpfsPubSub ps = new IpfsPubSub(api); long seq = file.lastSeq();
            System.out.println("Líder PubSub pronto. <sensor> <temperatura>, rajada <n> ou sair.");
            while (true) {
                System.out.print("> "); if (!sc.hasNextLine()) break;
                String[] p = sc.nextLine().trim().split("\\s+");
                if (p.length == 1 && p[0].equalsIgnoreCase("sair")) break;
                try {
                    if (p.length == 2 && p[0].equalsIgnoreCase("rajada")) seq = burst(Integer.parseInt(p[1]), seq, file, ps);
                    else if (p.length == 2) seq = sendOne(seq, p[0], Double.parseDouble(p[1]), file, ps);
                    else System.out.println("Formato inválido.");
                } catch (NumberFormatException e) { System.out.println("Número inválido."); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (IOException e) { System.out.println("IO: " + e.getMessage()); }
            }
        } catch (IOException e) { System.out.println("IO: " + e.getMessage()); }
    }
    private static long sendOne(long seq, String sensor, double temp, RecordFile file, IpfsPubSub ps) throws IOException, InterruptedException {
        SensorRecord r = SensorRecord.now(++seq, sensor, temp); file.append(r); ps.publish(TOPIC, r.toLine());
        System.out.println("Registado e publicado: " + r.toLine()); return seq;
    }
    private static long burst(int n, long seq, RecordFile file, IpfsPubSub ps) throws IOException, InterruptedException {
        if (n <= 0) { System.out.println("n deve ser positivo."); return seq; }
        long first = System.currentTimeMillis();
        for (int i = 0; i < n; i++) { SensorRecord r = SensorRecord.now(++seq, "SIM", ThreadLocalRandom.current().nextDouble(15, 30)); file.append(r); ps.publish(TOPIC, r.toLine()); }
        long last = System.currentTimeMillis(); System.out.printf(Locale.ROOT, "RAJADA: %d registos; primeiro=%d, último=%d, duração=%d ms%n", n, first, last, last-first); return seq;
    }
}
