package tcp01;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;

public class TCPClient {

    public static void main(String[] args) {
        Socket socket = null;

        try {
            String serverAddress = "localhost";
            int serverPort = 7896;

            socket = new Socket(serverAddress, serverPort);

            System.out.println("Ligação ao servidor estabelecida.");

            /*
             * Envia objetos para o ObjectInputStream
             * do servidor.
             */
            ObjectOutputStream out = new ObjectOutputStream(
                socket.getOutputStream()
            );

            /*
             * Recebe a resposta de texto enviada pelo servidor.
             */
            DataInputStream in = new DataInputStream(
                socket.getInputStream()
            );

            /*
             * Cria o Place que ficará associado à Person.
             */
            Place place = new Place(
                "3500-000",
                "Viseu"
            );

            /*
             * Cria a Person com a referência para o Place.
             */
            Person person = new Person(
                "Ana",
                place,
                2002
            );

            System.out.println(
                "Pessoa que será enviada: " + person
            );

            /*
             * Apenas a Person é escrita explicitamente.
             *
             * O Java encontra o Place através da referência
             * existente na Person e serializa-o automaticamente.
             */
            out.writeObject(person);
            out.flush();

            /*
             * O servidor devolve a localidade como texto.
             */
            String locality = in.readUTF();

            System.out.println(
                "Localidade recebida do servidor: " + locality
            );

        } catch (UnknownHostException e) {
            System.out.println("Sock: " + e.getMessage());

        } catch (EOFException e) {
            System.out.println(
                "EOF: o servidor fechou a ligação sem enviar "
                + "uma resposta completa."
            );

        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());

        } finally {
            if (socket != null) {
                try {
                    socket.close();
                    System.out.println("Ligação fechada.");
                } catch (IOException e) {
                    System.out.println(
                        "close: " + e.getMessage()
                    );
                }
            }
        }
    }
}