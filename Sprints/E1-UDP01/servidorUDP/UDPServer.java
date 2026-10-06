import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Servidor UDP com retenção de mensagens fora de ordem e entrega em cascata.
 *
 * Duas estruturas, cada uma respondendo à pergunta que lhe é feita com mais
 * frequência:
 *
 * - listaRececao : mensagens já ENTREGUES, por ordem.
 * Operação frequente: "acrescentar ao fim".
 * Como a entrega é sempre feita em sequência (nunca se entrega a N
 * sem já ter entregue a N-1), o índice da lista coincide sempre com
 * N-1: listaRececao.get(N-1) é a mensagem número N. Não é preciso
 * procurar por número — a posição já É o número.
 *
 * - estruturaTemporaria : mensagens RECEBIDAS mas ainda não entregues (chegaram
 * adiantadas).
 * Operação frequente: "tenho aqui a mensagem número x?" — uma
 * pergunta direta por chave, não uma pesquisa nem uma questão de
 * ordem. Por isso é um HashMap<Integer,String> (N -> texto) e não uma
 * lista: não preciso de saber qual é a mais antiga, preciso de saber
 * se um N concreto lá está.
 *
 * Uma mensagem pode estar RECEBIDA (chegou ao socket, entrou no processamento)
 * sem estar
 * ENTREGUE (na listaRececao): é exatamente o que acontece a uma mensagem que
 * fica retida na
 * estrutura temporária — foi recebida, mas a aplicação ainda não pode "vê-la"
 * como entregue
 * porque falta uma anterior.
 */
public class UDPServer {

    // Estado do servidor, acedido pelo método estático processDeliveredMessages.
    private static List<String> listaRececao = new ArrayList<>();
    private static Map<Integer, String> estruturaTemporaria = new HashMap<>();

    /**
     * Processes delivered messages
     * 
     * @return the last message processed in order
     */
    public static int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage,
            String currentMessage) {

        if (nCurrentMessage <= nLastMessageInOrder) {
            // Duplicado de uma mensagem já ENTREGUE (nCurrentMessage já passou por aqui
            // antes).
            // Não se acrescenta nada à listaRececao (já lá está) nem à estrutura temporária
            // (não faz sentido guardar como "adiantada" algo que já é passado).
            // L não muda: é isto que o CA5 pede para distinguir do duplicado ainda na
            // temporária.
            return nLastMessageInOrder;
        }

        if (nCurrentMessage == nLastMessageInOrder + 1) {
            // Mensagem em ordem: entra na lista de receção.
            listaRececao.add(currentMessage);
            int L = nCurrentMessage;

            // Entrega em cascata: enquanto a próxima mensagem esperada já estiver na
            // estrutura
            // temporária, entrega-se e remove-se de lá. O "enquanto" (while) é a construção
            // da
            // linguagem que exprime que o número de mensagens entregues não tem um limite
            // fixo —
            // pode ser zero, uma, ou várias, depende do que estava guardado.
            while (estruturaTemporaria.containsKey(L + 1)) {
                L = L + 1;
                // Remover da estrutura temporária é obrigatório: se ficasse lá, a mesma
                // mensagem
                // seria "entregue" outra vez mais tarde caso o L voltasse a passar por este
                // ponto,
                // e a estrutura temporária nunca esvaziaria — ficaria com lixo para sempre.
                listaRececao.add(estruturaTemporaria.remove(L));
            }

            // Devolve-se o número da ÚLTIMA mensagem processada em ordem, não
            // nCurrentMessage:
            // se a cascata avançou, esse valor é maior do que o que foi recebido agora.
            return L;

        } else {
            // Fora de ordem (adiantada): guarda-se na estrutura temporária.
            // putIfAbsent: se já lá estivesse uma mensagem com o mesmo número (duplicado de
            // uma
            // mensagem AINDA na estrutura temporária, não já entregue), o duplicado mais
            // recente
            // é descartado e o que já lá estava mantém-se.
            estruturaTemporaria.putIfAbsent(nCurrentMessage, currentMessage);

            // L não se altera quando a mensagem não é entregue.
            return nLastMessageInOrder;
        }
    }

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        // Mesmo significado e mesmo valor inicial da UDP001:
        // L = número da última mensagem ENTREGUE em ordem; 0 = "nada entregue ainda".
        int L = 0;

        try {
            aSocket = new DatagramSocket(6789);
            System.out.println("Servidor UDP (com retenção) à escuta no porto 6789.  L = " + L);

            while (true) {

                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                String recebido = new String(request.getData(), 0, request.getLength());
                System.out.println("\nRecebido: \"" + recebido + "\"");

                String[] partes = recebido.split(",", 2);
                Integer N = null;

                if (partes.length < 2) {
                    System.out.println("  -> mal formada (sem número de sequência)");
                } else {
                    try {
                        N = Integer.parseInt(partes[0].trim());
                    } catch (NumberFormatException e) {
                        System.out.println("  -> mal formada (N não é número)");
                    }
                }

                int Lantes = L;
                String textoResposta;
                DatagramPacket reply;

                if (N == null) {
                    // Mal formada: não se chama sequer o processDeliveredMessages, L mantém-se,
                    // e o servidor continua a funcionar normalmente a seguir.
                    textoResposta = "waitingfor," + (L + 1);
                    byte[] dados = textoResposta.getBytes();
                    reply = new DatagramPacket(dados, dados.length, request.getAddress(), request.getPort());

                } else {
                    // Aqui está toda a lógica de decisão: em ordem, fora de ordem, ou duplicado.
                    L = processDeliveredMessages(L, N, recebido);

                    if (L > Lantes) {
                        // A mensagem foi entregue (e possivelmente desencadeou uma cascata).
                        // Ecoa-se o que o cliente enviou agora.
                        textoResposta = recebido;
                        reply = new DatagramPacket(request.getData(), request.getLength(),
                                request.getAddress(), request.getPort());
                    } else {
                        // Não foi entregue: ou ficou na estrutura temporária, ou é um duplicado
                        // ignorado.
                        // Em ambos os casos a resposta é a mesma: pede-se a que falta.
                        textoResposta = "waitingfor," + (L + 1);
                        byte[] dados = textoResposta.getBytes();
                        reply = new DatagramPacket(dados, dados.length, request.getAddress(), request.getPort());
                    }
                }

                aSocket.send(reply);

                // Mensagens entregues NESTE passo: se L avançou, correspondem às posições
                // Lantes+1 .. L na listaRececao (lembrar: índice N-1 == mensagem N).
                StringBuilder entreguesAgora = new StringBuilder();
                for (int i = Lantes + 1; i <= L; i++) {
                    if (entreguesAgora.length() > 0)
                        entreguesAgora.append(" | ");
                    entreguesAgora.append(listaRececao.get(i - 1));
                }

                System.out.println("  Resposta: \"" + textoResposta + "\"   |   L = " + L);
                System.out.println("  Estrutura temporária: " + new TreeMap<>(estruturaTemporaria));
                System.out.println("  Entregues neste passo (" + (L - Lantes) + "): "
                        + (entreguesAgora.length() > 0 ? entreguesAgora : "nenhuma"));
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null) {
                // No fim, mostra-se a listaRececao completa e o estado final da temporária —
                // pedido explícito do ponto 11 da ficha.
                System.out.println("\n=== Lista de receção final (entregues, por ordem) ===");
                System.out.println(listaRececao);
                System.out.println("=== Estrutura temporária final ===");
                System.out.println(new TreeMap<>(estruturaTemporaria));
                aSocket.close();
            }
        }
    }
}