import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Servidor UDP02 com retenção e entrega em cascata de mensagens fora de ordem.
 *
 * Formato de cada mensagem: <N>,<texto>
 * L: número da última mensagem entregue em ordem.
 *
 * Esta implementação mantém o estado de uma única sequência lógica. Para
 * suportar vários clientes independentes seria necessário manter um ClientState
 * por par endereço/porto.
 */
public class UDPServer {

    private static final int SERVER_PORT = 6789;
    private static final int MAX_DATAGRAM_SIZE = 65_507;

    /*
     * Proteções contra crescimento ilimitado da estrutura temporária.
     * Uma mensagem demasiado adiantada é rejeitada e o cliente recebe
     * waitingfor,<L+1>.
     */
    private static final int MAX_SEQUENCE_GAP = 1_000;
    private static final int MAX_PENDING_MESSAGES = 1_000;

    /** Mensagens efetivamente entregues, sempre por ordem. */
    public static final List<String> listaRececao = new ArrayList<>();

    /** Mensagens recebidas mas ainda não entregues: número -> texto. */
    public static final Map<Integer, String> mapaTemporario = new HashMap<>();

    /**
     * Processa uma mensagem recebida.
     *
     * Se a mensagem for a próxima esperada, entrega-a e entrega em cascata
     * todas as mensagens consecutivas existentes na estrutura temporária.
     * Se estiver adiantada, guarda-a temporariamente, dentro dos limites de
     * segurança. Mensagens antigas, repetidas ou inválidas não alteram o estado.
     *
     * @param nLastMessageInOrder número da última mensagem entregue em ordem
     * @param nCurrentMessage número da mensagem atualmente recebida
     * @param currentMessage texto da mensagem atualmente recebida
     * @return número da última mensagem processada e entregue em ordem
     */
    public static int processDeliveredMessages(
            int nLastMessageInOrder,
            int nCurrentMessage,
            String currentMessage) {

        if (nCurrentMessage <= 0) {
            return nLastMessageInOrder;
        }

        if (nCurrentMessage == nLastMessageInOrder + 1) {
            listaRececao.add(formatMessage(nCurrentMessage, currentMessage));
            nLastMessageInOrder = nCurrentMessage;

            int nextMessage = nLastMessageInOrder + 1;
            while (mapaTemporario.containsKey(nextMessage)) {
                String pendingContent = mapaTemporario.remove(nextMessage);
                listaRececao.add(formatMessage(nextMessage, pendingContent));
                nLastMessageInOrder = nextMessage;
                nextMessage = nLastMessageInOrder + 1;
            }

            return nLastMessageInOrder;
        }

        if (nCurrentMessage > nLastMessageInOrder + 1) {
            long sequenceGap = (long) nCurrentMessage - nLastMessageInOrder;
            boolean withinAllowedGap = sequenceGap <= MAX_SEQUENCE_GAP;
            boolean hasCapacity = mapaTemporario.size() < MAX_PENDING_MESSAGES;

            if (withinAllowedGap && hasCapacity) {
                // Preserva a primeira cópia; uma repetição não substitui o conteúdo.
                mapaTemporario.putIfAbsent(nCurrentMessage, currentMessage);
            }
        }

        // Uma mensagem antiga, repetida ou retida não faz avançar L.
        return nLastMessageInOrder;
    }

    private static String formatMessage(int number, String content) {
        return number + "," + content;
    }

    private static void printState(
            int lastMessageInOrder,
            List<String> deliveredNow,
            String response) {

        // TreeMap é usado apenas para apresentar o HashMap por ordem numérica.
        Map<Integer, String> orderedPendingMessages = new TreeMap<>(mapaTemporario);

        System.out.println("  Resposta: \"" + response + "\"");
        System.out.println("  L após processamento: " + lastMessageInOrder);
        System.out.println("  Estrutura temporária: " + orderedPendingMessages);
        System.out.println("  Mensagens entregues neste passo: " + deliveredNow);
        System.out.println("  Lista de receção completa: " + listaRececao);
    }

    public static void main(String[] args) {
        int lastMessageInOrder = 0;

        try (DatagramSocket socket = new DatagramSocket(SERVER_PORT)) {
            System.out.println(
                    "Servidor UDP02 à escuta no porto " + SERVER_PORT
                            + ". L = " + lastMessageInOrder);

            while (true) {
                byte[] requestBuffer = new byte[MAX_DATAGRAM_SIZE];
                DatagramPacket request =
                        new DatagramPacket(requestBuffer, requestBuffer.length);
                socket.receive(request);

                String received = new String(
                        request.getData(),
                        request.getOffset(),
                        request.getLength(),
                        StandardCharsets.UTF_8);

                System.out.println();
                System.out.println("------------------------------------------------");
                System.out.println("Recebido: \"" + received + "\"");

                int listSizeBefore = listaRececao.size();
                boolean receivedMessageWasDelivered = false;
                String response;

                String[] parts = received.split(",", 2);

                if (parts.length != 2) {
                    System.out.println("  Resultado: mensagem mal formada (falta a vírgula).");
                    response = "waitingfor," + (lastMessageInOrder + 1);
                } else {
                    try {
                        int currentNumber = Integer.parseInt(parts[0].trim());
                        String currentContent = parts[1];

                        if (currentNumber <= 0) {
                            System.out.println(
                                    "  Resultado: mensagem mal formada "
                                            + "(N deve ser um inteiro positivo).");
                        } else {
                            int previousLastMessage = lastMessageInOrder;
                            boolean wasAlreadyPending =
                                    mapaTemporario.containsKey(currentNumber);
                            long sequenceGap =
                                    (long) currentNumber - previousLastMessage;

                            lastMessageInOrder = processDeliveredMessages(
                                    previousLastMessage,
                                    currentNumber,
                                    currentContent);

                            receivedMessageWasDelivered =
                                    lastMessageInOrder > previousLastMessage;

                            if (receivedMessageWasDelivered) {
                                System.out.println(
                                        "  Resultado: mensagem entregue; "
                                                + "a cascata terminou em L = "
                                                + lastMessageInOrder + ".");
                            } else if (currentNumber <= previousLastMessage) {
                                System.out.println(
                                        "  Resultado: duplicada ou antiga; foi ignorada.");
                            } else if (wasAlreadyPending) {
                                System.out.println(
                                        "  Resultado: duplicada ainda pendente; "
                                                + "a primeira cópia foi preservada.");
                            } else if (sequenceGap > MAX_SEQUENCE_GAP) {
                                System.out.println(
                                        "  Resultado: número demasiado adiantado; "
                                                + "a mensagem não foi retida.");
                            } else if (!mapaTemporario.containsKey(currentNumber)) {
                                System.out.println(
                                        "  Resultado: estrutura temporária cheia; "
                                                + "a mensagem não foi retida.");
                            } else {
                                System.out.println(
                                        "  Resultado: mensagem fora de ordem; "
                                                + "foi retida temporariamente.");
                            }
                        }

                        /*
                         * A confirmação é cumulativa: ok,L informa o cliente de
                         * que todas as mensagens até L já foram entregues em ordem.
                         * Por exemplo, se a receção de 2 libertar também 3 e 4,
                         * a resposta enviada será ok,4.
                         */
                        response = receivedMessageWasDelivered
                                ? "ok," + lastMessageInOrder
                                : "waitingfor," + (lastMessageInOrder + 1);

                    } catch (NumberFormatException exception) {
                        System.out.println(
                                "  Resultado: mensagem mal formada "
                                        + "(N não é um inteiro válido).");
                        response = "waitingfor," + (lastMessageInOrder + 1);
                    }
                }

                List<String> deliveredNow = new ArrayList<>(
                        listaRececao.subList(listSizeBefore, listaRececao.size()));

                byte[] responseData = response.getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(
                        responseData,
                        responseData.length,
                        request.getAddress(),
                        request.getPort());
                socket.send(reply);

                printState(lastMessageInOrder, deliveredNow, response);
            }

        } catch (SocketException exception) {
            System.err.println("Erro ao criar ou utilizar o socket: "
                    + exception.getMessage());
        } catch (IOException exception) {
            System.err.println("Erro de comunicação: " + exception.getMessage());
        }
    }
}
