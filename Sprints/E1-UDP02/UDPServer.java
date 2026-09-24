import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servidor UDP com deteção de mensagens fora de ordem e gestão de cascatas.
 * Formato esperado das mensagens: <N>,<texto>
 */
public class UDPServer {

    static Map<Integer, String> temp = new HashMap<>();
    static List<String> rececao = new ArrayList<>();

    public static int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage, String currentMessage) {

        // Ignorar mensagens duplicadas ou já entregues (N <= L)
        if (nCurrentMessage <= nLastMessageInOrder) {
            return nLastMessageInOrder;
        }

        // SE VIER ORDENADA
        if (nCurrentMessage == nLastMessageInOrder + 1) {
            rececao.add(currentMessage);
            nLastMessageInOrder++;

            // Descarregar cascata acumulada no temp
            while (temp.get(nLastMessageInOrder + 1) != null) {
                rececao.add(temp.get(nLastMessageInOrder + 1));
                temp.remove(nLastMessageInOrder + 1);
                nLastMessageInOrder++;
            }
        }
        // SE VIER FORA DE ORDEM
        else {
            temp.put(nCurrentMessage, currentMessage);
        }

        return nLastMessageInOrder;
    }

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        int L = 0;

        try {
            aSocket = new DatagramSocket(6789);
            System.out.println("Servidor UDP à escuta no porto 6789.  L = " + L);

            while (true) {
                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                String recebido = new String(request.getData(), 0, request.getLength());
                System.out.println("\nRecebido: \"" + recebido + "\"");

                String[] partes = recebido.split(",", 2);

                int lAntes = L;
                boolean entregueOuProcessada = false;
                int N = -1; // Guardar N para diagnóstico de prints

                if (partes.length < 2) {
                    System.out.println("  -> mal formada (sem número de sequência)");
                } else {
                    try {
                        N = Integer.parseInt(partes[0].trim());

                        L = processDeliveredMessages(L, N, partes[1].trim());

                        if (L > lAntes) {
                            entregueOuProcessada = true;
                        } else {
                            if (N <= lAntes) {
                                System.out.println("  -> duplicada ou antiga (descartada, N = " + N + ")");
                            } else {
                                System.out.println("  -> fora de ordem (guardada no temp, N = " + N + ")");
                            }
                        }

                    } catch (NumberFormatException e) {
                        System.out.println("  -> mal formada (N não é número)");
                    }
                }

                DatagramPacket reply;
                String textoResposta;

                if (entregueOuProcessada) {
                    textoResposta = recebido;
                    reply = new DatagramPacket(request.getData(), request.getLength(),
                            request.getAddress(), request.getPort());
                    System.out.println("  -> entregue: echo");
                } else {
                    textoResposta = "waitingfor," + (L + 1);
                    byte[] dados = textoResposta.getBytes();
                    reply = new DatagramPacket(dados, dados.length,
                            request.getAddress(), request.getPort());
                    System.out.println("  -> a aguardar: " + textoResposta);
                }

                aSocket.send(reply);

                // Prints de diagnóstico corrigidos e rigorosos
                int entreguesNestePasso = L - lAntes;
                List<String> entreguesAgora = new ArrayList<>();
                if (entreguesNestePasso > 0) {
                    entreguesAgora = rececao.subList(rececao.size() - entreguesNestePasso, rececao.size());
                }

                System.out.println("  [Estado] L = " + L + " | temp = " + temp +
                        " | entregues neste passo (" + entreguesNestePasso + ") = " + entreguesAgora);
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