import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Servidor UDP com retenção de mensagens fora de ordem.
 *
 * Formato esperado das mensagens: <N>,<texto>
 * Estado mantido: L = número da última mensagem entregue em ordem
 * Valor inicial: L = 0
 *
 * Regras:
 * - Se N == L+1: entrega a mensagem e todas as consecutivas já retidas (cascata).
 * - Se N > L+1: guarda no buffer temporário e responde waitingfor,<L+1>.
 * - Se N <= L ou N já existir no buffer: duplicado, ignora e responde waitingfor,<L+1>.
 * - Mensagem mal formada: não termina, responde waitingfor,<L+1>.
 */
public class UDPServer {

    // Lista de receção: mensagens já entregues à aplicação, por ordem.
    private static final List<String> deliveredMessages = new ArrayList<>();

    // Estrutura temporária: mensagens recebidas fora de ordem, indexadas por N.
    private static final Map<Integer, String> pendingBuffer = new HashMap<>();

    // Última mensagem entregue em ordem.
    private static int L = 0;

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            System.out.println("Servidor UDP à escuta no porto 6789. L = " + L);

            while (true) {
                // Buffer novo a cada iteração para evitar contaminação entre datagramas.
                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                String received = new String(request.getData(), 0, request.getLength());
                System.out.println("\nRecebido: \"" + received + "\"");

                String[] parts = received.split(",", 2);
                String responseText;

                if (parts.length < 2) {
                    System.out.println("  -> mal formada (sem numero de sequencia)");
                    responseText = "waitingfor," + (L + 1);
                } else {
                    try {
                        int N = Integer.parseInt(parts[0].trim());
                        String messageText = parts[1];
                        int previousL = L;

                        L = processDeliveredMessages(L, N, messageText);

                        if (L != previousL) {
                            // Mensagem entregue (possivelmente em cascata): echo.
                            responseText = received;
                        } else {
                            // Não entregue: pede a seguinte.
                            responseText = "waitingfor," + (L + 1);
                        }

                        // Impressão do estado obrigatória para demonstração.
                        System.out.println("  -> L = " + L);
                        System.out.println("  -> Buffer temporario: " + pendingBuffer);

                        if (L != previousL) {
                            System.out.print("  -> Entregues neste passo: ");
                            for (int i = previousL + 1; i <= L; i++) {
                                System.out.print(i + ":" + deliveredMessages.get(i - 1));
                                if (i < L) {
                                    System.out.print(", ");
                                }
                            }
                            System.out.println();
                        } else {
                            System.out.println("  -> Nenhuma mensagem entregue neste passo");
                        }

                    } catch (NumberFormatException e) {
                        System.out.println("  -> mal formada (N nao e numero)");
                        responseText = "waitingfor," + (L + 1);
                    }
                }

                byte[] responseData = responseText.getBytes();
                DatagramPacket reply = new DatagramPacket(responseData, responseData.length,
                        request.getAddress(), request.getPort());
                aSocket.send(reply);
                System.out.println("  Resposta: \"" + responseText + "\"");
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null) {
                aSocket.close();
            }
        }
    }

    public static int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage, String currentMessage) {
        // Duplicado de uma mensagem já entregue.
        if (nCurrentMessage <= nLastMessageInOrder) {
            return nLastMessageInOrder;
        }

        // Duplicado de uma mensagem já retida no buffer.
        if (pendingBuffer.containsKey(nCurrentMessage)) {
            return nLastMessageInOrder;
        }

        if (nCurrentMessage == nLastMessageInOrder + 1) {
            // Entrega a mensagem atual.
            deliveredMessages.add(currentMessage);
            nLastMessageInOrder = nCurrentMessage;

            // Cascata: entrega consecutivas já retidas.
            while (pendingBuffer.containsKey(nLastMessageInOrder + 1)) {
                int nextN = nLastMessageInOrder + 1;
                String nextMessage = pendingBuffer.remove(nextN);
                deliveredMessages.add(nextMessage);
                nLastMessageInOrder = nextN;
            }

            return nLastMessageInOrder;
        } else {
            // Fora de ordem: guarda temporariamente.
            pendingBuffer.put(nCurrentMessage, currentMessage);
            return nLastMessageInOrder;
        }
    }
}
