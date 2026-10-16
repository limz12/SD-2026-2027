package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Scanner;

/**
 * Líder da replicação com MULTICAST IP.
 *
 * - Desacoplamento no ESPAÇO: o líder envia UM datagrama para o endereço de grupo e
 *   não sabe quem, nem quantos, são os recetores (não há lista de réplicas em lado nenhum).
 * - Sem desacoplamento no TEMPO: quem não estiver à escuta no momento do send perde o registo.
 * - send() não espera resposta nem confirmação: "enviado" não quer dizer "no ficheiro da réplica".
 *   O líder só consegue saber a primeira coisa.
 * - Ordem "escrever localmente, depois propagar": assim a réplica nunca tem um registo que o
 *   líder não tem. Se fosse ao contrário e o líder falhasse entre os dois passos, as réplicas
 *   ficavam com um registo que o ficheiro do líder (a fonte da verdade) não tem.
 */
public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    static MulticastSocket socket;
    static InetAddress group;
    static RecordFile file;
    static long seq; // só o líder numera: crescente e sem saltos

    // 2) propaga: um único datagrama para o grupo
    static void enviar(SensorRecord r) throws IOException {
        byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(m, m.length, group, PORT));
    }

    public static void main(String[] args) {
        try (MulticastSocket s = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {
            socket = s;
            group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1); // 0 = não sai do próprio host; 1 = fica na rede local; 32 = atravessaria vários routers (se o multicast fosse encaminhado)
            file = new RecordFile("lider.txt");
            seq = file.lastSeq(); // continua a numeração após reinício
            Random rnd = new Random();
            System.out.println("Líder multicast pronto (" + GROUP + ":" + PORT + ", seq atual = " + seq + ").");
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
                    } catch (RuntimeException e) { // sem argumento ou não numérico
                        System.out.println("Uso: rajada <n>");
                        continue;
                    }
                    long primeiro = 0, ultimo = 0;
                    for (int i = 0; i < n; i++) {
                        double t = Math.round((15 + rnd.nextDouble() * 15) * 10) / 10.0;
                        SensorRecord r = SensorRecord.now(++seq, "SIM", t);
                        file.append(r); // 1) local
                        if (i == 0) primeiro = System.currentTimeMillis();
                        enviar(r);      // 2) propaga, o mais depressa possível, sem pausas
                        ultimo = System.currentTimeMillis();
                    }
                    long dur = Math.max(1, ultimo - primeiro);
                    System.out.println("Rajada de " + n + " registos (seq até " + seq + ")");
                    System.out.println("  primeiro enviado em " + primeiro + " ms | último enviado em " + ultimo + " ms");
                    System.out.println("  tempo de envio = " + dur + " ms | débito = " + (n * 1000L / dur) + " registos/s");
                    continue;
                }

                if (p[0].equalsIgnoreCase("desordem")) {
                    // Modo de teste do ponto 6: regista 3 registos pela ordem certa no ficheiro,
                    // mas ENVIA o 2.º e o 3.º ANTES do 1.º. (Pode ser removido depois do teste.)
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
                enviar(r);      // 2) propaga para o grupo (não espera resposta)
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}