package rep01;

import java.io.*;
import java.util.Random;
import java.util.Scanner;

/**
 * Líder da replicação com PUBSUB do IPFS. Comportamento idêntico ao MulticastLeader
 * (mesmo formato de registo, mesmos comandos); só muda a PROPAGAÇÃO:
 * em vez de um DatagramPacket para um endereço de grupo, publica-se no tópico
 * "rep01-temperaturas" através da API HTTP do nó IPFS local.
 *
 * Aqui o líder fala com UM só interlocutor: o daemon local. Se o daemon estiver parado,
 * o publish falha com ConnectException NESTE programa (ao contrário do multicast, onde
 * o send nunca falha por falta de recetores).
 */
public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";

    static IpfsPubSub ipfs;
    static RecordFile file;
    static long seq;

    static void enviar(SensorRecord r) throws IOException, InterruptedException {
        ipfs.publish(TOPIC, r.toLine()); // pedido HTTP síncrono ao daemon; devolve 200 sem corpo
    }

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        ipfs = new IpfsPubSub(api);
        try (Scanner sc = new Scanner(System.in)) {
            file = new RecordFile("lider.txt");
            seq = file.lastSeq();
            Random rnd = new Random();
            System.out.println("Líder PubSub pronto (tópico " + TOPIC + ", API " + api + ", seq atual = " + seq + ").");
            System.out.println("Comandos: <sensor> <temperatura> | rajada <n> | desordem | sair");

            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break; // BLOQUEIA à espera do utilizador (CLI)
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\\s+");

                if (p[0].equalsIgnoreCase("rajada")) {
                    int n;
                    try {
                        n = Integer.parseInt(p[1]);
                    } catch (RuntimeException e) {
                        System.out.println("Uso: rajada <n>");
                        continue;
                    }
                    long primeiro = 0, ultimo = 0;
                    for (int i = 0; i < n; i++) {
                        double t = Math.round((15 + rnd.nextDouble() * 15) * 10) / 10.0;
                        SensorRecord r = SensorRecord.now(++seq, "SIM", t);
                        file.append(r); // 1) local
                        if (i == 0) primeiro = System.currentTimeMillis();
                        enviar(r);      // 2) publica (cada publish bloqueia até o daemon responder 200)
                        ultimo = System.currentTimeMillis();
                    }
                    long dur = Math.max(1, ultimo - primeiro);
                    System.out.println("Rajada de " + n + " registos (seq até " + seq + ")");
                    System.out.println("  primeiro enviado em " + primeiro + " ms | último enviado em " + ultimo + " ms");
                    System.out.println("  tempo de envio = " + dur + " ms | débito = " + (n * 1000L / dur) + " registos/s");
                    continue;
                }

                if (p[0].equalsIgnoreCase("desordem")) {
                    SensorRecord a = SensorRecord.now(++seq, "DES", 20.0);
                    SensorRecord b = SensorRecord.now(++seq, "DES", 21.0);
                    SensorRecord c = SensorRecord.now(++seq, "DES", 22.0);
                    file.append(a); file.append(b); file.append(c);
                    enviar(b); enviar(c); enviar(a);
                    System.out.println("Enviados fora de ordem: " + b.getSeq() + ", " + c.getSeq() + ", " + a.getSeq());
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r); // 1) escreve localmente
                enviar(r);      // 2) publica no tópico
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            // Ex.: ConnectException (daemon parado). O registo já ficou em lider.txt (passo 1)
            // mas NÃO chegou às réplicas: divergência que o líder só descobre por esta exceção.
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}