import java.net.*;
import java.io.*;
import java.util.*;

public class clienteUDP {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket();

            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;

            Scanner s = new Scanner(System.in);

            System.out.print("Modo (a=automático, m=manual): ");
            String modo = s.nextLine().trim();

            int N = 1;

            System.out.print("Introduza a mensagem (ou 'sair'): ");
            String linha = s.nextLine();

            while (!linha.equals("sair")) {
                String envio;

                if (modo.equalsIgnoreCase("a")) {
                    envio = N + "," + linha;
                    N++;
                } else {
                    envio = linha;
                }

                byte[] sendBuffer = envio.getBytes();
                DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, aHost, serverPort);
                aSocket.send(sendPacket);

                byte[] receiveBuffer = new byte[1000];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                aSocket.receive(receivePacket);

                String resposta = new String(receivePacket.getData(), 0, receivePacket.getLength()).trim();

                if (resposta.startsWith("waitingfor,")) {
                    System.out.println("[AVISO] Servidor rejeitou. À espera de: " + resposta.split(",")[1]);
                } else {
                    System.out.println("[SUCESSO] " + resposta);
                }

                System.out.print("Introduza a mensagem (ou 'sair'): ");
                linha = s.nextLine();
            }

        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}