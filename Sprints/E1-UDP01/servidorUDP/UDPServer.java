import java.net.*;
import java.io.*;

/**
 * Servidor UDP com deteção de mensagens fora de ordem.
 *
 * Formato esperado das mensagens: <N>,<texto>
 * Estado mantido: L = número da última mensagem aceite em ordem
 * Valor inicial: L = 0 ("ainda não foi aceite nada")
 * Regra de decisão: se N == L+1 -> echo e L = N
 * caso contrário -> "waitingfor,<L+1>", L inalterado
 */
public class UDPServer {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        // Estado mínimo do servidor: L = 0 significa "nada aceite ainda",
        // logo a próxima mensagem esperada é a 1.
        int L = 0;

        try {
            aSocket = new DatagramSocket(6789);
            System.out.println("Servidor UDP à escuta no porto 6789.  L = " + L);

            while (true) {

                // Buffer criado dentro do ciclo: uma mensagem curta nunca fica
                // contaminada pelos bytes de uma mensagem anterior mais longa.
                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                // getData() é o recipiente todo (1000 bytes); getLength() são os válidos.
                String recebido = new String(request.getData(), 0, request.getLength());
                System.out.println("\nRecebido: \"" + recebido + "\"");

                // Partir em 2: tudo o que vem depois da PRIMEIRA vírgula é texto,
                // mesmo que o texto contenha vírgulas.
                String[] partes = recebido.split(",", 2);

                boolean emOrdem = false;

                if (partes.length < 2) {
                    // Mal formada: não tem vírgula. O servidor não termina nem mexe em L.
                    System.out.println("  -> mal formada (sem número de sequência)");
                } else {
                    try {
                        int N = Integer.parseInt(partes[0].trim());
                        if (N == L + 1) {
                            L = N;
                            emOrdem = true;
                        } else {
                            System.out.println("  -> fora de ordem (N = " + N + ", esperado " + L + "+1)");
                        }
                    } catch (NumberFormatException e) {
                        // N não é um número. Mesma política: responde e continua.
                        System.out.println("  -> mal formada (N não é número)");
                    }
                }

                DatagramPacket reply;
                String textoResposta;

                if (emOrdem) {
                    // Echo: reenvia os bytes recebidos, usando request.getLength()
                    // e não buffer.length, para não arrastar a cauda do buffer.
                    textoResposta = recebido;
                    reply = new DatagramPacket(request.getData(), request.getLength(),
                            request.getAddress(), request.getPort());
                    System.out.println("  -> em ordem: echo");
                } else {
                    // Mensagem descartada (não é guardada); pede-se a que falta.
                    textoResposta = "waitingfor," + (L + 1);
                    byte[] dados = textoResposta.getBytes();
                    reply = new DatagramPacket(dados, dados.length,
                            request.getAddress(), request.getPort());
                }

                // Endereço e porto do destinatário vêm do próprio datagrama recebido.
                aSocket.send(reply);

                System.out.println("  Resposta: \"" + textoResposta + "\"   |   L = " + L);
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null)
                aSocket.close();
        }
    }
}