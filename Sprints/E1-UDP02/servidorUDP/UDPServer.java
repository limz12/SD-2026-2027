import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Servidor UDP multi-cliente com retenção de mensagens fora de ordem,
 * entrega em cascata, e deteção explícita de duplicados/mensagens antigas.
 *
 * Antes, L, a listaRececao e a estruturaTemporaria eram campos estáticos da
 * classe — um único L para TODOS os clientes. Isso não fazia sentido: a
 * numeração 1,2,3,... é do cliente, não do servidor. Dois clientes diferentes
 * a enviar as suas próprias mensagens "1,..." ao mesmo tempo pisavam-se um ao
 * outro no mesmo L. Agora cada cliente tem a sua própria sessão, isolada.
 */
public class UDPServer {

    /**
     * Estado de UM cliente: o seu L, a sua lista de receção e a sua estrutura
     * temporária. Cada cliente novo arranca com uma ClientSession vazia, com o
     * mesmo valor inicial e o mesmo significado de sempre (L=0, "nada entregue
     * ainda a este cliente").
     */
    private static class ClientSession {
        int L = 0;
        List<String> listaRececao = new ArrayList<>();
        Map<Integer, String> estruturaTemporaria = new HashMap<>();

        // Motivo pelo qual a última mensagem processada NÃO foi entregue, para
        // construir a resposta: "duplicada" (é exatamente a última entregue, ou
        // já estava à espera na estrutura temporária), "antiga" (é anterior à
        // última entregue — uma retransmissão tardia de algo já ultrapassado),
        // ou "" quando nem chega a ser um duplicado (é simplesmente adiantada e
        // está a ser vista pela primeira vez).
        String motivoRejeicao = "";

        /**
         * Processes delivered messages for this client's session.
         * 
         * @return the last message processed in order
         */
        int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage,
                String currentMessage) {

            if (nCurrentMessage == nLastMessageInOrder) {
                // É exatamente a mensagem que acabou de ser entregue: duplicado
                // imediato. Não se mexe na listaRececao (já lá está) nem na estrutura
                // temporária (não faz sentido guardá-la como "adiantada").
                motivoRejeicao = "duplicada";
                return nLastMessageInOrder;
            }

            if (nCurrentMessage < nLastMessageInOrder) {
                // Mais antiga do que a última entregue: uma retransmissão tardia de
                // uma mensagem já ultrapassada há mais de um passo.
                motivoRejeicao = "antiga";
                return nLastMessageInOrder;
            }

            if (nCurrentMessage == nLastMessageInOrder + 1) {
                // Em ordem: entra na lista de receção.
                listaRececao.add(currentMessage);
                int L = nCurrentMessage;

                // Entrega em cascata: enquanto a próxima mensagem esperada já estiver
                // na estrutura temporária, entrega-se e remove-se de lá.
                while (estruturaTemporaria.containsKey(L + 1)) {
                    L = L + 1;
                    listaRececao.add(estruturaTemporaria.remove(L));
                }

                motivoRejeicao = ""; // entregue: não há rejeição a explicar
                return L;
            }

            // Fora de ordem (adiantada, N > L+1).
            if (estruturaTemporaria.containsKey(nCurrentMessage)) {
                // Já lá estava uma mensagem com este número: NÃO se subscreve
                // (não se sobrescreve) — o duplicado mais recente é descartado e o
                // que já lá estava mantém-se intocado.
                motivoRejeicao = "duplicada";
            } else {
                estruturaTemporaria.put(nCurrentMessage, currentMessage);
                motivoRejeicao = "";
            }
            return nLastMessageInOrder;
        }
    }

    // Uma sessão por cliente, indexada por endereço + porto de ORIGEM do
    // datagrama. É esta combinação — não o conteúdo da mensagem — que diz "de
    // quem" é cada pedido: o porto do cliente é efémero (atribuído pelo SO
    // quando ele cria o seu DatagramSocket), mas mantém-se fixo durante toda a
    // execução desse cliente, servindo como identificador da sua sessão.
    private static Map<SocketAddress, ClientSession> clientes = new HashMap<>();

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            System.out.println("Servidor UDP (multi-cliente, com retenção) à escuta no porto 6789.");

            while (true) {

                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                SocketAddress origem = new InetSocketAddress(request.getAddress(), request.getPort());
                ClientSession sessao = clientes.computeIfAbsent(origem, k -> new ClientSession());

                String recebido = new String(request.getData(), 0, request.getLength());
                System.out.println("\n[" + origem + "] Recebido: \"" + recebido + "\"");

                String[] partes = recebido.split(",", 2);
                Integer N = null;

                if (partes.length < 2) {
                    System.out.println("  -> mal formada (sem número de sequência)");
                } else {
                    try {
                        int candidato = Integer.parseInt(partes[0].trim());
                        if (candidato < 1) {
                            // Números de sequência válidos começam em 1; 0 ou negativos
                            // são tratados como mal formados, não como "mensagem 0".
                            System.out.println("  -> mal formada (N tem de ser >= 1)");
                        } else {
                            N = candidato;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("  -> mal formada (N não é número)");
                    }
                }

                int Lantes = sessao.L;
                String textoResposta;
                DatagramPacket reply;

                if (N == null) {
                    textoResposta = "waitingfor," + (sessao.L + 1);
                    byte[] dados = textoResposta.getBytes();
                    reply = new DatagramPacket(dados, dados.length, request.getAddress(), request.getPort());

                } else {
                    sessao.L = sessao.processDeliveredMessages(sessao.L, N, recebido);

                    if (sessao.L > Lantes) {
                        // Entregue (possivelmente com cascata): ecoa-se o que chegou agora.
                        textoResposta = recebido;
                        reply = new DatagramPacket(request.getData(), request.getLength(),
                                request.getAddress(), request.getPort());
                    } else {
                        // Não entregue: ao "waitingfor" junta-se o motivo, quando há um,
                        // para o cliente distinguir "está simplesmente adiantada" de
                        // "já tínhamos isto" (duplicada) ou "isto já passou" (antiga).
                        String prefixo = sessao.motivoRejeicao.isEmpty() ? "" : sessao.motivoRejeicao + ",";
                        textoResposta = prefixo + "waitingfor," + (sessao.L + 1);
                        byte[] dados = textoResposta.getBytes();
                        reply = new DatagramPacket(dados, dados.length, request.getAddress(), request.getPort());
                    }
                }

                aSocket.send(reply);

                StringBuilder entreguesAgora = new StringBuilder();
                for (int i = Lantes + 1; i <= sessao.L; i++) {
                    if (entreguesAgora.length() > 0)
                        entreguesAgora.append(" | ");
                    entreguesAgora.append(sessao.listaRececao.get(i - 1));
                }

                System.out.println("  Resposta: \"" + textoResposta + "\"   |   L = " + sessao.L);
                System.out.println("  Estrutura temporária: " + new TreeMap<>(sessao.estruturaTemporaria));
                System.out.println("  Entregues neste passo (" + (sessao.L - Lantes) + "): "
                        + (entreguesAgora.length() > 0 ? entreguesAgora : "nenhuma"));
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null) {
                System.out.println("\n=== Estado final, por cliente ===");
                for (Map.Entry<SocketAddress, ClientSession> entrada : clientes.entrySet()) {
                    ClientSession s = entrada.getValue();
                    System.out.println(entrada.getKey() + "  ->  L=" + s.L
                            + "  lista=" + s.listaRececao
                            + "  temp=" + new TreeMap<>(s.estruturaTemporaria));
                }
                aSocket.close();
            }
        }
    }
}