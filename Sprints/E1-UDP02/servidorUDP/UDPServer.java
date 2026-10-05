import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servidor UDP com deteção de mensagens fora de ordem, gestão de cascatas,
 * rejeição de duplicados e estado independente para cada cliente.
 *
 * Formato esperado das mensagens: <N>,<texto>
 */
public class UDPServer {

    private static final int SERVER_PORT = 6789;
    private static final int BUFFER_SIZE = 1000;

    /**
     * Cada cliente tem o seu próprio L, mapa temporário e lista de receção.
     */
    private static class ClientState {
        int L = 0;
        Map<Integer, String> temp = new HashMap<>();
        List<String> rececao = new ArrayList<>();
    }

    /**
     * Associa cada cliente ao respetivo estado.
     *
     * A chave SocketAddress contém o endereço IP e o porto de origem. Assim,
     * dois clientes com o mesmo IP mas portos diferentes mantêm estados distintos.
     */
    private static final Map<SocketAddress, ClientState> clientes = new HashMap<>();

    /**
     * Processa uma mensagem no estado do cliente que a enviou.
     *
     * @return o número da última mensagem entregue em ordem para esse cliente
     */
    public static int processDeliveredMessages(
            ClientState estado,
            int nCurrentMessage,
            String currentMessage) {

        // Ignorar mensagens duplicadas ou já entregues (N <= L).
        if (nCurrentMessage <= estado.L) {
            return estado.L;
        }

        // Se N já estiver no mapa temporário, descartar a segunda mensagem.
        // A primeira mensagem recebida com esse N permanece guardada.
        if (estado.temp.containsKey(nCurrentMessage)) {
            return estado.L;
        }

        // Mensagem recebida pela ordem esperada.
        if (nCurrentMessage == estado.L + 1) {
            estado.rececao.add(nCurrentMessage + "," + currentMessage);
            estado.L++;

            // Entregar em cascata as mensagens consecutivas guardadas no temp.
            while (estado.temp.containsKey(estado.L + 1)) {
                int proximoN = estado.L + 1;
                String proximaMensagem = estado.temp.remove(proximoN);
                estado.rececao.add(proximoN + "," + proximaMensagem);
                estado.L++;
            }
        } else {
            // Mensagem adiantada: guardar apenas a primeira cópia desse N.
            estado.temp.put(nCurrentMessage, currentMessage);
        }

        return estado.L;
    }

    public static void main(String[] args) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(SERVER_PORT);
            System.out.println(
                    "Servidor UDP à escuta no porto " + SERVER_PORT + ".");

            while (true) {
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket request =
                        new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                /*
                 * getSocketAddress() devolve a combinação IP + porto de origem.
                 * Se for um cliente novo, é criado um estado com L = 0.
                 */
                SocketAddress identificadorCliente = request.getSocketAddress();
                ClientState estado = clientes.computeIfAbsent(
                        identificadorCliente,
                        chave -> new ClientState());

                String recebido = new String(
                        request.getData(),
                        request.getOffset(),
                        request.getLength(),
                        StandardCharsets.UTF_8);

                System.out.println();
                System.out.println("Recebido de " + identificadorCliente
                        + ": \"" + recebido + "\"");

                String[] partes = recebido.split(",", 2);
                int lAntes = estado.L;
                int tamanhoRececaoAntes = estado.rececao.size();
                boolean entregueOuProcessada = false;
                boolean duplicadaNoTemp = false;
                int N = -1;

                if (partes.length < 2) {
                    System.out.println(
                            "  -> mal formada (sem número de sequência)");
                } else {
                    try {
                        N = Integer.parseInt(partes[0].trim());
                        String mensagem = partes[1].trim();

                        if (N <= 0) {
                            System.out.println(
                                    "  -> mal formada (N deve ser positivo)");
                        } else {
                            duplicadaNoTemp = estado.temp.containsKey(N);

                            processDeliveredMessages(estado, N, mensagem);

                            if (estado.L > lAntes) {
                                entregueOuProcessada = true;
                                System.out.println(
                                        "  -> entregue; confirmação cumulativa até L = "
                                                + estado.L);
                            } else if (N <= lAntes) {
                                System.out.println(
                                        "  -> duplicada ou antiga, já entregue "
                                                + "(descartada, N = " + N + ")");
                            } else if (duplicadaNoTemp) {
                                System.out.println(
                                        "  -> N já existe no temp; segunda mensagem "
                                                + "descartada (N = " + N + ")");
                            } else {
                                System.out.println(
                                        "  -> fora de ordem "
                                                + "(guardada no temp, N = " + N + ")");
                            }
                        }
                    } catch (NumberFormatException e) {
                        System.out.println(
                                "  -> mal formada (N não é número)");
                    }
                }

                String textoResposta;
                if (entregueOuProcessada) {
                    // ok,L confirma que todas as mensagens até L foram entregues.
                    textoResposta = "ok," + estado.L;
                } else {
                    textoResposta = "waitingfor," + (estado.L + 1);
                }

                byte[] dadosResposta =
                        textoResposta.getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(
                        dadosResposta,
                        dadosResposta.length,
                        request.getAddress(),
                        request.getPort());
                aSocket.send(reply);

                List<String> entreguesAgora = new ArrayList<>(
                        estado.rececao.subList(
                                tamanhoRececaoAntes,
                                estado.rececao.size()));

                System.out.println("  -> resposta: " + textoResposta);
                System.out.println("  [Cliente] " + identificadorCliente);
                System.out.println("  [Estado] L = " + estado.L
                        + " | temp = " + estado.temp
                        + " | entregues neste passo ("
                        + entreguesAgora.size() + ") = " + entreguesAgora);
                System.out.println("  [Receção completa] " + estado.rececao);
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
}
