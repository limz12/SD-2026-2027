package tcp01;

import java.io.*;
import java.net.*;

/**
 * Cliente TCP da ficha TCP01.
 *
 * Estabelece uma ligação ao servidor (localhost:7896), envia-lhe um objeto
 * {@link Person} — que transporta consigo um {@link Place} — e recebe como
 * resposta, em texto simples, a localidade dessa pessoa.
 *
 * Cada sentido da ligação usa um tipo de stream: objetos para enviar,
 * texto para receber.
 */
public class TCPClient {

    public static void main(String[] args) {
        Socket s = null;
        try {
            int serverPort = 7896;                                              // porto onde o servidor está à escuta

            s = new Socket("localhost", serverPort);                            // BLOQUEIA: aguarda o aperto de mão TCP; ConnectException se ninguém escutar no porto

            ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());   // ao ser criada, escreve um cabeçalho no fluxo (é o que permite ao servidor criar o seu ObjectInputStream)
            oos.writeObject(new Person("Ana", new Place("3500", "Viseu"), 2005));   // envia APENAS a Person; o Place viaja dentro do grafo de objetos alcançáveis
            oos.flush();                                                            // empurra os bytes para o socket

            DataInputStream in = new DataInputStream(s.getInputStream());
            String data = in.readUTF();                                         // BLOQUEIA: aguarda a resposta completa do servidor; EOFException se este fechar sem responder
            System.out.println("Received: " + data);

        } catch (UnknownHostException e) {
            System.out.println("Sock: " + e.getMessage());
        } catch (java.io.NotSerializableException e) {
            System.out.println("Nao serializavel: " + e.getMessage());          // alguma classe do grafo não é serializável
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());                       // o servidor fechou a ligação sem responder
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());                        // inclui ConnectException (servidor inacessível)
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    System.out.println("close: " + e.getMessage());
                }
            }
        }
    }
}
