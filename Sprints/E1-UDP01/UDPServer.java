import javax.xml.crypto.Data;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class UDPServer {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];

            System.out.println("Socket a correr em localhost:"+6789);

            int ordemAtual = 0;

            while (true) {
                //RECEBER DADOS DO CLIENTE
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                //VERIFICAR ORDENACAO -- (Movido int ordemAtual para fora do ciclo)

                String mensagemCliente = new String(request.getData(),0,request.getLength());

                //IR BUSCAR A POS ATE A ,
                int pos = mensagemCliente.indexOf(',');
                if(pos == -1){
                    if (mensagemCliente.contentEquals("!q"))
                    {
                        String erro = "Adeus cliente :)";
                        System.out.println(erro);

                        DatagramPacket respostaErro = new DatagramPacket(erro.getBytes(),erro.length(), request.getAddress(),request.getPort());
                        aSocket.send(respostaErro);
                    } else {
                        String erro = "ERRO! A mensagem do cliente nao segue o padrao <N>,<mensagem>";
                        System.out.println(erro);

                        DatagramPacket respostaErro = new DatagramPacket(erro.getBytes(),erro.length(), request.getAddress(),request.getPort());
                        aSocket.send(respostaErro);
                    }

                    continue;
                }


                //SE FOR O ATUAL
                String checkOrdem = String.valueOf(ordemAtual+1);
                System.out.println("Valor do checkOrdem: "+checkOrdem);
                System.out.println("Valor da ordem da mensagem do cliente: "+mensagemCliente.substring(0,pos));

                if(mensagemCliente.substring(0,pos).equals(checkOrdem) && mensagemCliente != null){

                    //RESPOSTA DO SERVIDOR (apenas com a nova mensagem)
                    DatagramPacket respostaCliente = new DatagramPacket(mensagemCliente.getBytes(),
                            request.getLength(), request.getAddress(), request.getPort());
                    aSocket.send(respostaCliente);

                    ordemAtual++;

                } else {
                    System.out.println("ERRO! A mensagem do cliente nao está ordenada ou então está vazia, à espera da ordem correta....");

                    String erro = "waitingfor,"+String.valueOf(ordemAtual+1);
                    System.out.println(erro);

                    DatagramPacket respostaErro = new DatagramPacket(erro.getBytes(),erro.length(), request.getAddress(),request.getPort());
                    aSocket.send(respostaErro);
                    continue;

                }


                System.out.println("Recebi cliente: " +mensagemCliente);

            }
        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}