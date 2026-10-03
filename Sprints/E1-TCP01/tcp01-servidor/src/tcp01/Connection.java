package tcp01;

import java.io.*;
import java.net.*;

/**
 * Atende uma ligação aceite pelo {@link TCPServer}, numa thread própria.
 *
 * O construtor guarda o socket, cria as streams e arranca a thread
 * ({@code this.start()}); o pedido em si é processado em {@code run()},
 * que já corre fora da thread main.
 *
 * Um tipo de stream por sentido: objetos para receber, texto para responder.
 */
public class Connection extends Thread {

    ObjectInputStream in;
    DataOutputStream out;
    Socket clientSocket;

    public Connection(Socket aClientSocket) {
        try {
            clientSocket = aClientSocket;
            in  = new ObjectInputStream(clientSocket.getInputStream());    // o cabeçalho do ObjectOutputStream do cliente já foi enviado, por isso não fica preso à criação
            out = new DataOutputStream(clientSocket.getOutputStream());
            this.start();                                                  // arranca run() numa thread separada; a main segue para o accept()
        } catch (IOException e) {
            System.out.println("Connection: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            Object o = in.readObject();                                         // BLOQUEIA: aguarda um objeto completo do cliente
            Person p = (Person) o;                                              // readObject() devolve Object: o cast é obrigatório
            out.writeUTF(p.getPlace().getLocality());                           // responde com a localidade que chegou dentro do grafo da Person
        } catch (java.lang.ClassNotFoundException e) {
            System.out.println("Classe desconhecida: " + e.getMessage());       // a classe do objeto recebido não existe neste projeto
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());                       // o cliente fechou a ligação sem enviar o objeto
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());                        // inclui InvalidClassException (versões incompatíveis) e NotSerializableException ("writing aborted")
        } finally {
            try {
                clientSocket.close();                                           // cada thread fecha o SEU socket, não o do accept()
            } catch (IOException e) {
                /* falha ao fechar */
            }
        }
    }
}

