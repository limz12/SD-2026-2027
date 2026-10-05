package tcp01;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;

public class Connection extends Thread {

    private ObjectInputStream in;
    private DataOutputStream out;
    private Socket clientSocket;

    public Connection(Socket clientSocket) {
        this.clientSocket = clientSocket;

        // Cria uma nova thread que executará o método run().
        this.start();
    }

    @Override
    public void run() {
        try {
            /*
             * Recebe objetos enviados pelo ObjectOutputStream
             * do cliente.
             */
            in = new ObjectInputStream(
                clientSocket.getInputStream()
            );

            /*
             * Envia texto para o DataInputStream do cliente.
             */
            out = new DataOutputStream(
                clientSocket.getOutputStream()
            );

            /*
             * Bloqueia até receber um objeto completo.
             */
            Object receivedObject = in.readObject();

            /*
             * O objeto recebido é do tipo geral Object.
             * Por isso, é necessário fazer o cast para Person.
             */
            Person person = (Person) receivedObject;

            System.out.println(
                "Pessoa recebida: " + person.getName()
            );

            System.out.println(
                "Ano: " + person.getYear()
            );

            System.out.println(
                "Código postal: "
                + person.getPlace().getPostalCode()
            );

            System.out.println(
                "Localidade: "
                + person.getPlace().getLocality()
            );

            /*
             * Na Fase C, o servidor devolve a localidade
             * da pessoa, em vez do nome.
             */
            String locality = person.getPlace().getLocality();

            out.writeUTF(locality);
            out.flush();

        } catch (EOFException e) {
            System.out.println(
                "EOF: o cliente fechou a ligação antes de enviar "
                + "o objeto completo."
            );

        } catch (ClassNotFoundException e) {
            System.out.println(
                "Classe não encontrada: " + e.getMessage()
            );

        } catch (ClassCastException e) {
            System.out.println(
                "O objeto recebido não é uma Person: "
                + e.getMessage()
            );

        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());

        } finally {
            try {
                clientSocket.close();
                System.out.println("Ligação ao cliente fechada.");
            } catch (IOException e) {
                System.out.println(
                    "Erro ao fechar ligação: " + e.getMessage()
                );
            }
        }
    }
}