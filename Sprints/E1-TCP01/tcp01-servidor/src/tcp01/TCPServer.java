package tcp01;

import java.io.*;
import java.net.*;

/**
 * Servidor TCP da ficha TCP01.
 *
 * Cria um {@link ServerSocket} no porto 7896 e entra num ciclo infinito de
 * {@code accept()}: cada ligação aceite é entregue a uma {@link Connection},
 * que a processa noutra thread — assim a thread principal volta de imediato
 * ao {@code accept()} e o servidor atende vários clientes em paralelo.
 *
 * Deve ser arrancado antes do cliente.
 */
public class TCPServer {

    public static void main(String[] args) {
        try {
            int serverPort = 7896;
            ServerSocket listenSocket = new ServerSocket(serverPort);   // associa o porto a este processo; falha se estiver ocupado

            while (true) {
                Socket clientSocket = listenSocket.accept();            // BLOQUEIA: a thread main fica aqui à espera que um cliente se ligue
                Connection c = new Connection(clientSocket);            // cria a thread que atende esta ligação e volta de imediato ao accept()
            }
        } catch (IOException e) {
            System.out.println("Listen: " + e.getMessage());            // inclui BindException (porto já em uso)
        }
    }
}
