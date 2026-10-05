package tcp01;

import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String[] args) {
        Socket s = null;
        try {
            int serverPort = 7896;

            // new Socket(...) BLOQUEIA até a ligação ficar estabelecida (o
            // handshake TCP) ou falhar. Se não houver servidor à escuta, falha
            // logo aqui com ConnectException ("Connection refused") — não chega
            // sequer a tentar escrever nada.
            s = new Socket("localhost", serverPort);

            // ObjectOutputStream, para enviar o objeto Person. A sua construção
            // escreve de imediato um pequeno cabeçalho na stream — é esse
            // cabeçalho que desbloqueia o ObjectInputStream do servidor, do outro
            // lado. Como só este sentido (cliente -> servidor) transporta
            // objetos, criar este stream aqui, sem mais cuidados de ordem, chega:
            // não há um ObjectInputStream do lado do cliente à espera de um
            // cabeçalho que o servidor nunca escreveria.
            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());

            // A resposta continua a ser texto simples: DataInputStream, sem
            // handshake, não bloqueia na construção.
            DataInputStream in = new DataInputStream(s.getInputStream());

            Place place = new Place("3500-000", "Viseu");
            Person person = new Person("Ana", place, 1998);

            // Escreve-se APENAS a Person. O Place chega ao servidor por arrasto:
            // writeObject percorre todo o grafo de objetos alcançável a partir de
            // person e serializa-os em conjunto — condição única: todas as
            // classes alcançáveis (aqui, também Place) têm de ser Serializable.
            out.writeObject(person);
            out.flush();

            // readUTF() BLOQUEIA até chegar uma string UTF completa, ou lança
            // EOFException se o servidor fechar a ligação antes de responder
            // (por exemplo, por ter rejeitado o objeto a meio da leitura).
            String data = in.readUTF();
            System.out.println("Received: " + data);

        } catch (UnknownHostException e) {
            System.out.println("Sock: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());
        } catch (IOException e) {
            // Cobre também NotSerializableException: se Place (ou Person) não
            // implementasse Serializable, é AQUI, no writeObject, que a escrita
            // falhava — do lado de quem tenta enviar, não de quem recebe.
            System.out.println("IO: " + e.getMessage());
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