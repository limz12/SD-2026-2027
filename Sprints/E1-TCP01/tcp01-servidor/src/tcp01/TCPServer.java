package tcp01;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPServer {

    public static void main(String[] args) {
        int serverPort = 7896;

        try {
            ServerSocket listenSocket = new ServerSocket(serverPort);

            System.out.println(
                "Servidor iniciado no porto " + serverPort
            );

            while (true) {
                System.out.println("À espera de um cliente...");

                // Bloqueia até um cliente estabelecer uma ligação.
                Socket clientSocket = listenSocket.accept();

                System.out.println(
                    "Cliente ligado: "
                    + clientSocket.getRemoteSocketAddress()
                );

                // Cada cliente é processado numa thread própria.
                new Connection(clientSocket);
            }

        } catch (IOException e) {
            System.out.println("Listen: " + e.getMessage());
        }
    }
}