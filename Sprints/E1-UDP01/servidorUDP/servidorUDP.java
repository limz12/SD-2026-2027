import java.net.*;
import java.io.*;

public class servidorUDP {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];

            int L = 0;

            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                String linhaRecebida = new String(request.getData(), 0, request.getLength());

                String resposta;
                String[] partes = linhaRecebida.split(",", 2);

                if (partes.length < 2) {
                    resposta = "waitingfor," + (L + 1);
                } else {
                    try {
                        int N = Integer.parseInt(partes[0].trim());
                        String mensagem = partes[1].trim();

                        if (N == L + 1) {
                            L = N;
                            resposta = "echo," + mensagem;
                        } else {
                            resposta = "waitingfor," + (L + 1);
                        }

                    } catch (NumberFormatException e) {
                        resposta = "waitingfor," + (L + 1);
                    }
                }

                System.out.println("Recebido: " + linhaRecebida + " | Resposta: " + resposta + " | L=" + L);

                byte[] sendData = resposta.getBytes();
                DatagramPacket reply = new DatagramPacket(sendData, sendData.length,
                        request.getAddress(), request.getPort());
                aSocket.send(reply);
            }
        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}
