package tcp01;

import java.io.*;
import java.net.*;

public class TCPServer {
    public static void main(String[] args) {
        try {
            int serverPort = 7896;
            ServerSocket listenSocket = new ServerSocket(serverPort);
            System.out.println("Servidor TCP à escuta no porto " + serverPort);

            while (true) {
                // accept() BLOQUEIA: fica parado aqui até um cliente se ligar.
                // Esta é a única operação de rede na thread principal — é por isso
                // que ela está sempre disponível para o próximo cliente, mesmo que
                // o anterior ainda esteja a ser atendido (essa parte foi entregue
                // à Connection, noutra thread).
                Socket clientSocket = listenSocket.accept();

                // O construtor da Connection cria as streams E arranca a thread
                // (this.start()); o pedido deste cliente passa a correr em paralelo
                // com o ciclo do accept(), que volta já à próxima iteração.
                Connection c = new Connection(clientSocket);
            }
        } catch (IOException e) {
            System.out.println("Listen: " + e.getMessage());
        }
    }
}