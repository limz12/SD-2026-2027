import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Servidor UDP02 com suporte a retenção de mensagens fora de ordem (Buffer Temporário).
 *
 * Formato esperado das mensagens: <N>,<texto>
 * Estado mantido: L = número da última mensagem aceite em ordem
 */
public class UDPServer {

    // 1. Declaração das estruturas de dados
    public static List<String> listaRececao = new ArrayList<>();
    public static Map<Integer, String> mapaTemporario = new HashMap<>();

    // 2. Método exigido para processar as mensagens recebidas
    public static int processDeliveredMessages(int nLastMessageInOrder, int nMessageReceived, String contentReceived) {
        
        // Se for a mensagem exatamente esperada
        if (nMessageReceived == nLastMessageInOrder + 1) {
            // Insere na lista e avança o L
            listaRececao.add(contentReceived);
            nLastMessageInOrder++;
            
            // Cascata: verifica se as próximas mensagens já estão retidas no mapa
            while (mapaTemporario.containsKey(nLastMessageInOrder + 1)) {
                String pendingContent = mapaTemporario.remove(nLastMessageInOrder + 1);
                listaRececao.add(pendingContent);
                nLastMessageInOrder++;
            }
            
        } 
        // Se for uma mensagem adiantada (maior que a esperada), guarda no buffer
        // (CA5: Se a mensagem for repetida enquanto já está retida no mapa, 
        // o put() simplesmente sobrescreve o valor anterior sem impacto estrutural).
        else if (nMessageReceived > nLastMessageInOrder + 1) {
            mapaTemporario.put(nMessageReceived, contentReceived);
        }
        
        // Se for <= nLastMessageInOrder, é duplicada/antiga, não faz nada
        
        // Retorna o estado atualizado do L
        return nLastMessageInOrder;
    }

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        int L = 0;

        try {
            aSocket = new DatagramSocket(6789);
            System.out.println("Servidor UDP02 à escuta no porto 6789.  L = " + L);

            while (true) {
                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                String recebido = new String(request.getData(), 0, request.getLength());
                System.out.println("\n------------------------------------------------");
                System.out.println("Recebido: \"" + recebido + "\"");

                String[] partes = recebido.split(",", 2);
                boolean emOrdem = false;

                if (partes.length < 2) {
                    System.out.println("  -> mal formada (sem número de sequência)");
                } else {
                    try {
                        int N = Integer.parseInt(partes[0].trim());
                        String texto = partes[1];
                        
                        // Guarda o tamanho da lista antes do processamento para saber quantas foram entregues agora
                        int tamanhoAntes = listaRececao.size();
                        
                        // 3. Chamada ao método de processamento
                        int novoL = processDeliveredMessages(L, N, texto);
                        
                        // Avalia o que aconteceu com base no retorno do método
                        if (novoL > L) {
                            L = novoL;
                            emOrdem = true;
                            
                            // Extrai as mensagens que foram efetivamente entregues nesta iteração (incluindo cascatas)
                            List<String> entreguesAgora = listaRececao.subList(tamanhoAntes, listaRececao.size());
                            System.out.println("  -> processada(s) e entregue(s): " + entreguesAgora);
                            
                        } else if (novoL == L && N > L + 1) {
                            System.out.println("  -> retida no buffer (N = " + N + ", esperado " + (L + 1) + ")");
                            emOrdem = false;
                        } else {
                            System.out.println("  -> duplicada ou antiga (N = " + N + ")");
                            emOrdem = false;
                        }
                        
                    } catch (NumberFormatException e) {
                        System.out.println("  -> mal formada (N não é número)");
                    }
                }

                DatagramPacket reply;
                String textoResposta;

                if (emOrdem) {
                    textoResposta = recebido;
                    reply = new DatagramPacket(request.getData(), request.getLength(),
                            request.getAddress(), request.getPort());
                } else {
                    textoResposta = "waitingfor," + (L + 1);
                    byte[] dados = textoResposta.getBytes();
                    reply = new DatagramPacket(dados, dados.length,
                            request.getAddress(), request.getPort());
                }

                aSocket.send(reply);
                
                // Impressões de estado (CA4)
                System.out.println("  Resposta: \"" + textoResposta + "\"   |   L = " + L);
                System.out.println("  [Estado] Lista Completa: " + listaRececao);
                System.out.println("  [Estado] Em Espera: " + mapaTemporario.keySet());
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