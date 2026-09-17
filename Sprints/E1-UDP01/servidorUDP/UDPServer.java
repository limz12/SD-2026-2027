import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class UDPServer {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];
            
            int L = 0; 

            System.out.println("Servidor UDP iniciado na porta 6789. À espera de mensagens...");

            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                // Força a leitura com UTF-8
                String receivedString = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8);
                
                String[] parts = receivedString.split(",", 2);

                if (parts.length < 2) {
                    System.out.println("Ignorado: Datagrama malformado (sem separador) -> " + receivedString);
                    continue; 
                }

                int N;
                try {
                    N = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException e) {
                    System.out.println("Ignorado: Datagrama malformado (N não é um número válido) -> " + receivedString);
                    continue; 
                }

                String text = parts[1];
                byte[] replyData;

                if (N == L + 1) {
                    L = N; 
                    // Força a conversão do echo para UTF-8
                    replyData = receivedString.getBytes(StandardCharsets.UTF_8); 
                    System.out.println("Aceite: Sequência " + N + " | Mensagem: " + text);
                } else {
                    String waitMsg = "waitingfor," + (L + 1);
                    // Força a conversão da mensagem de erro para UTF-8
                    replyData = waitMsg.getBytes(StandardCharsets.UTF_8);
                    System.out.println("Rejeitado: Recebeu " + N + " mas esperava " + (L + 1) + ". A enviar: " + waitMsg);
                }

                DatagramPacket reply = new DatagramPacket(replyData, replyData.length, request.getAddress(), request.getPort());
                aSocket.send(reply);
            }
        } catch (SocketException e) { 
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) { 
            System.out.println("IO: " + e.getMessage());
        } finally { 
            if (aSocket != null) aSocket.close(); 
        }
    }
}