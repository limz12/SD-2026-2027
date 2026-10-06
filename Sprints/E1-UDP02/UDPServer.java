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
import java.util.TreeMap;

/**
 * Servidor UDP com:
 * - retenção de mensagens fora de ordem;
 * - entrega em cascata;
 * - deteção de duplicados e mensagens antigas;
 * - estado independente para cada cliente.
 *
 * Formato das mensagens: <N>,<texto>
 */
public class UDPServer {

    private static final int SERVER_PORT = 6789;
    private static final int BUFFER_SIZE = 1000;

    /**
     * Estado pertencente a um único cliente.
     *
     * Cada cliente possui o seu próprio L, lista de receção e mapa
     * temporário. Assim, a numeração de um cliente não interfere com a de outro.
     */
    private static class ClientState {
        int L = 0;
        List<String> rececao = new ArrayList<>();
        Map<Integer, String> temp = new HashMap<>();

        /* Motivo pelo qual a última mensagem não foi entregue. */
        String motivoRejeicao = "";
    }

    /**
     * Uma entrada por cliente. SocketAddress identifica a origem através da
     * combinação do endereço IP com o porto do cliente.
     */
    private static final Map<SocketAddress, ClientState> clientes =
            new HashMap<>();

    /**
     * Processa uma mensagem no estado do cliente que a enviou.
     *
     * @return o número da última mensagem entregue em ordem para esse cliente
     */
    private static int processDeliveredMessages(
            ClientState estado,
            int nCurrentMessage,
            String currentMessage) {

        // Limpa o resultado do processamento anterior.
        estado.motivoRejeicao = "";

        // N igual a L: repetição da última mensagem entregue.
        if (nCurrentMessage == estado.L) {
            estado.motivoRejeicao = "duplicada";
            return estado.L;
        }

        // N menor que L: mensagem que já tinha sido entregue anteriormente.
        if (nCurrentMessage < estado.L) {
            estado.motivoRejeicao = "antiga";
            return estado.L;
        }

        // O mesmo N já está guardado no mapa temporário.
        // A primeira cópia é preservada e a segunda é descartada.
        if (estado.temp.containsKey(nCurrentMessage)) {
            estado.motivoRejeicao = "duplicada";
            return estado.L;
        }

        // Mensagem recebida pela ordem esperada.
        if (nCurrentMessage == estado.L + 1) {
            estado.rececao.add(nCurrentMessage + "," + currentMessage);
            estado.L = nCurrentMessage;

            // Entrega em cascata todas as mensagens consecutivas já guardadas.
            while (estado.temp.containsKey(estado.L + 1)) {
                int proximoN = estado.L + 1;
                String proximaMensagem = estado.temp.remove(proximoN);

                estado.rececao.add(proximoN + "," + proximaMensagem);
                estado.L = proximoN;
            }

            return estado.L;
        }

        // N > L+1: chegou adiantada e fica guardada até a lacuna ser preenchida.
        estado.temp.put(nCurrentMessage, currentMessage);
        return estado.L;
    }

    public static void main(String[] args) {
        DatagramSocket socket = null;

        try {
            socket = new DatagramSocket(SERVER_PORT);
            System.out.println(
                    "Servidor UDP à escuta no porto " + SERVER_PORT + ".");

            while (true) {
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket request =
                        new DatagramPacket(buffer, buffer.length);

                // Bloqueia até chegar um datagrama.
                socket.receive(request);

                /*
                 * IP + porto identificam o cliente durante esta execução.
                 * Se ainda não existir, é criado um estado novo com L = 0.
                 */
                SocketAddress cliente = request.getSocketAddress();
                ClientState estado = clientes.computeIfAbsent(
                        cliente,
                        chave -> new ClientState());

                String recebido = new String(
                        request.getData(),
                        request.getOffset(),
                        request.getLength(),
                        StandardCharsets.UTF_8);

                System.out.println();
                System.out.println("[" + cliente + "] Recebido: \""
                        + recebido + "\"");

                String[] partes = recebido.split(",", 2);
                int lAntes = estado.L;
                int tamanhoAntes = estado.rececao.size();
                boolean entregue = false;

                if (partes.length < 2) {
                    estado.motivoRejeicao = "";
                    System.out.println(
                            "  -> mal formada: falta o número ou a vírgula");
                } else {
                    try {
                        int N = Integer.parseInt(partes[0].trim());
                        String mensagem = partes[1].trim();

                        if (N < 1) {
                            estado.motivoRejeicao = "";
                            System.out.println(
                                    "  -> mal formada: N deve ser positivo");
                        } else {
                            processDeliveredMessages(estado, N, mensagem);
                            entregue = estado.L > lAntes;

                            if (entregue) {
                                System.out.println(
                                        "  -> entregue; a cascata terminou em L = "
                                                + estado.L);
                            } else if (estado.motivoRejeicao.equals("duplicada")) {
                                System.out.println(
                                        "  -> duplicada: segunda cópia descartada");
                            } else if (estado.motivoRejeicao.equals("antiga")) {
                                System.out.println(
                                        "  -> antiga: já tinha sido entregue");
                            } else {
                                System.out.println(
                                        "  -> fora de ordem: guardada no temp");
                            }
                        }
                    } catch (NumberFormatException e) {
                        estado.motivoRejeicao = "";
                        System.out.println(
                                "  -> mal formada: N não é um número");
                    }
                }

                String resposta;

                if (entregue) {
                    // Confirma cumulativamente todas as mensagens até L.
                    resposta = "ok," + estado.L;
                } else if (!estado.motivoRejeicao.isEmpty()) {
                    // Informa se a mensagem era duplicada ou antiga.
                    resposta = estado.motivoRejeicao
                            + ",waitingfor," + (estado.L + 1);
                } else {
                    // Mensagem nova fora de ordem ou mal formada.
                    resposta = "waitingfor," + (estado.L + 1);
                }

                byte[] dadosResposta =
                        resposta.getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(
                        dadosResposta,
                        dadosResposta.length,
                        request.getAddress(),
                        request.getPort());
                socket.send(reply);

                List<String> entreguesAgora = new ArrayList<>(
                        estado.rececao.subList(
                                tamanhoAntes,
                                estado.rececao.size()));

                System.out.println("  Resposta: \"" + resposta + "\"");
                System.out.println("  L = " + estado.L);
                System.out.println("  Temp = " + new TreeMap<>(estado.temp));
                System.out.println("  Entregues neste passo = " + entreguesAgora);
                System.out.println("  Lista de receção = " + estado.rececao);
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }
}
