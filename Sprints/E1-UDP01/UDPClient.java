import javax.xml.crypto.Data;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket();

            //timeout maximo para obter resposta do socket
            //aSocket.setSoTimeout(2000);

            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;
            /* REMOVIDO PARA NAO TER PROBLEMAS COM O CHECK DA ORDEM DOS PACOTES
            byte[] m = "vou enviar esta mensagem ao servidor".getBytes();

            DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);

            aSocket.send(request);

            byte[] buffer = new byte[1000];

            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);

            aSocket.receive(reply);

            //Resposta ao CA2: apenas com new String(reply.getData), ele converte o comprimento do buffer para string dai ter muitos caracteres que nao sao mensagem.
            //para resolver isso foi dado um offset de 0, para este buffer quero que respondas desde o 0 "primeira posicao" até ao final  "m.lenght"
            System.out.println("Reply: " + new String(reply.getData(),0,m.length));
             */

            //ler do teclado 4.1
            Scanner sc = new Scanner(System.in);
            String inputTeclado ="";
            int ordemMensagem = 1;
            do {
                System.out.print("> ");

                String textoLido = sc.nextLine();

                // Usamos 'continue' em vez de 'return' para o ciclo não fechar o programa e pedir novo input.
                if (textoLido.isBlank()) {
                    System.out.println("Não pode conter espaços brancos ou estar vazio.");
                    continue;
                }

                // Monta a string final com a ordem (comentar embaixo e descomentar a seguinte para alterar a ordem dos pacotes manualmente)
                //inputTeclado = ordemMensagem + "," + textoLido;
                inputTeclado = textoLido;

                // enviar linha para o servidor
                byte mensagem[] = inputTeclado.getBytes();
                DatagramPacket pacoteMensagem = new DatagramPacket(mensagem, mensagem.length, aHost, serverPort);
                aSocket.send(pacoteMensagem);
                ordemMensagem++;

                //receber a resposta do servidor (bloqueante ele fica a espera da resposta)
                byte[] bufferResposta = new byte[1000];
                DatagramPacket resposta = new DatagramPacket(bufferResposta, bufferResposta.length);
                aSocket.receive(resposta);


                System.out.println("Reply: " + new String(resposta.getData(), 0, resposta.getLength()));

            } while (!inputTeclado.contains("!q"));

        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}