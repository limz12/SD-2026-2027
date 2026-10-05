package tcp01;

import java.io.*;
import java.net.*;

public class Connection extends Thread {

    ObjectInputStream in; // agora lê OBJETOS (Person), não texto
    DataOutputStream out; // a resposta continua a ser texto simples
    Socket clientSocket;

    public Connection(Socket aClientSocket) {
        try {
            clientSocket = aClientSocket;

            // Construir um ObjectInputStream BLOQUEIA até chegar o cabeçalho que
            // o ObjectOutputStream do cliente escreve quando é construído do
            // outro lado. Aqui só este sentido da ligação (cliente -> servidor)
            // transporta objetos — o sentido de resposta usa DataOutputStream,
            // sem handshake — por isso não há risco de os dois lados ficarem à
            // espera um do outro.
            in = new ObjectInputStream(clientSocket.getInputStream());

            out = new DataOutputStream(clientSocket.getOutputStream());

            // run() passa a correr numa thread PRÓPRIA desta ligação, criada e
            // arrancada por start(). Se, em vez disto, se chamasse run() aqui
            // diretamente, o código de run() executava dentro desta MESMA thread
            // — a do construtor, chamada a partir do ciclo do accept() — e o
            // servidor só voltava ao accept() depois deste pedido terminar: um
            // segundo cliente a tentar ligar-se ficava à espera na fila de
            // ligações do sistema operativo, sem sequer ser aceite.
            this.start();

        } catch (IOException e) {
            System.out.println("Connection: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            // Bloqueia até chegar um objeto completo. readObject() devolve
            // Object — nunca Person diretamente — por isso o cast é obrigatório.
            // Se o cliente tivesse enviado um objeto de outra classe, o cast
            // lançaria ClassCastException (não seria apanhada pelos catches
            // abaixo, que são de IOException/ClassNotFoundException — seria um
            // erro de runtime diferente, a assinalar que o protocolo não foi
            // respeitado).
            Object recebido = in.readObject();
            Person person = (Person) recebido;

            // A localidade só está disponível aqui porque o Place "veio junto"
            // com a Person: o cliente nunca chamou writeObject(place). É a prova
            // pedida no ponto 10 — writeObject percorreu o grafo alcançável a
            // partir da Person e serializou o Place automaticamente, porque ele
            // também implementa Serializable.
            String localidade = person.getPlace().getLocality();
            System.out.println("Pessoa recebida: " + person.getName()
                    + " (" + person.getYear() + ")  em  " + person.getPlace());

            out.writeUTF(localidade);

        } catch (ClassNotFoundException e) {
            // A classe do objeto recebido (Person, ou Place dentro dela) não
            // existe aqui com o mesmo nome completo — nome E pacote. É o erro
            // típico de "Person num pacote diferente no servidor": a classe que
            // chegou chama-se, por exemplo, outro.tcp01.Person, e esta JVM não a
            // conhece com esse nome.
            System.out.println("ClassNotFound: " + e.getMessage());
        } catch (EOFException e) {
            // A ligação fechou antes de chegar um objeto completo — por exemplo,
            // o cliente fechou logo a seguir a ligar-se, sem sequer escrever
            // nada. Isto não diz nada sobre a rede nem sobre o servidor: diz que
            // QUEM ESCREVIA parou a meio ou nunca começou.
            System.out.println("EOF: " + e.getMessage());
        } catch (IOException e) {
            // Cobre, entre outras causas, InvalidClassException (serialVersionUID
            // diferente dos dois lados — o objeto chega, mas é REJEITADO na
            // leitura porque a versão não bate certo com a classe local).
            System.out.println("IO: " + e.getMessage());
        } finally {
            // O fecho acontece AQUI, no finally do run(), e não na thread
            // principal, porque é esta thread — e só ela — que detém e usa este
            // Socket. A thread principal já voltou ao accept() e nunca teve uma
            // referência a este clientSocket depois de o passar ao construtor.
            try {
                clientSocket.close();
            } catch (IOException e) {
                /* falha ao fechar */
            }
        }
    }
}