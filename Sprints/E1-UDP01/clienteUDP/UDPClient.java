import java.net.*;
import java.io.*;
import java.util.Scanner;
import java.nio.charset.StandardCharsets;

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        Scanner scanner = new Scanner(System.in, "UTF-8"); // Garante que o Scanner lê em UTF-8

        try {
            aSocket = new DatagramSocket();
            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;
            
            System.out.println("Escolha o modo de funcionamento do Cliente UDP:");
            System.out.println("1 - Automático (O cliente gere os números de sequência)");
            System.out.println("2 - Manual (O utilizador insere N,mensagem manualmente)");
            System.out.print("Opção: ");
            
            int modo = 0;
            while (modo != 1 && modo != 2) {
                try {
                    modo = Integer.parseInt(scanner.nextLine().trim());
                    if (modo != 1 && modo != 2) {
                        System.out.print("Opção inválida. Escolha 1 ou 2: ");
                    }
                } catch (NumberFormatException e) {
                    System.out.print("Entrada inválida. Escolha 1 ou 2: ");
                }
            }

            int nextSequence = 1;

            System.out.println("\n--- Cliente Iniciado ---");
            if (modo == 1) {
                System.out.println("Modo AUTOMÁTICO. Escreva apenas a mensagem (ex: olá).");
            } else {
                System.out.println("Modo MANUAL. Escreva no formato N,mensagem (ex: 1,olá).");
            }
            System.out.println("Escreva 'sair' para terminar.\n");

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine();

                if (input.trim().equalsIgnoreCase("sair")) {
                    System.out.println("A encerrar o cliente...");
                    break;
                }

                String messageToSend = "";

                if (modo == 1) {
                    messageToSend = nextSequence + "," + input;
                } else {
                    String[] parts = input.split(",", 2);
                    if (parts.length < 2) {
                        System.out.println("Erro: Formato inválido. Falta a vírgula separadora.");
                        continue;
                    }
                    try {
                        Integer.parseInt(parts[0].trim());
                    } catch (NumberFormatException e) {
                        System.out.println("Erro: O valor de sequência não é um número válido.");
                        continue;
                    }
                    messageToSend = input; 
                }

                // Força a codificação para UTF-8 antes de enviar
                byte[] m = messageToSend.getBytes(StandardCharsets.UTF_8);
                DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
                aSocket.send(request);

                byte[] buffer = new byte[1000];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(reply);

                // Força a leitura com UTF-8
                String replyString = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
                
                if (replyString.startsWith("waitingfor,")) {
                    System.out.println(">> [ALERTA DE ORDEM] Servidor pede: " + replyString);
                } else {
                    System.out.println(">> [ECHO NORMAL] Servidor aceitou: " + replyString);
                }

                if (modo == 1) {
                    if (replyString.startsWith("waitingfor,")) {
                        String[] replyParts = replyString.split(",");
                        if (replyParts.length > 1) {
                            nextSequence = Integer.parseInt(replyParts[1].trim());
                        }
                    } else {
                        nextSequence++;
                    }
                }
            }

        } catch (SocketException e) { 
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) { 
            System.out.println("IO: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Erro inesperado: " + e.getMessage());
        } finally { 
            if (aSocket != null) aSocket.close();
            if (scanner != null) scanner.close();
        }
    }
}