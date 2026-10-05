import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Cliente UDP.
 *
 * - lê mensagens do teclado e termina com "fim";
 * - no modo automático numera as mensagens sequencialmente;
 * - no modo manual permite provocar desordenação;
 * - interpreta waitingfor,<N> e a confirmação cumulativa ok,<L>;
 * - acompanha os números enviados que ainda estão pendentes no servidor.
 */
public class UDPClient {

    private static final String SAIR = "fim";
    private static final String PREFIXO_WAITING = "waitingfor,";
    private static final String PREFIXO_OK = "ok,";
    private static final int SERVER_PORT = 6789;
    private static final int BUFFER_SIZE = 1000;
    private static final int TIMEOUT_MILLISECONDS = 5000;

    public static void main(String[] args) {
        DatagramSocket aSocket = null;
        BufferedReader teclado = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        // Próximo número sugerido no modo automático.
        int proximo = 1;

        /*
         * Números que este cliente enviou fora de ordem e que, segundo as
         * respostas recebidas, ainda podem estar no mapa temporário do servidor.
         */
        Set<Integer> mensagensPendentes = new HashSet<>();

        try {
            // O sistema operativo atribui um porto de origem livre ao cliente.
            aSocket = new DatagramSocket();
            aSocket.setSoTimeout(TIMEOUT_MILLISECONDS);

            InetAddress aHost = InetAddress.getByName("localhost");

            System.out.println(
                    "Cliente UDP. Escreva \"" + SAIR + "\" para terminar.");
            System.out.println("Porto local do cliente: " + aSocket.getLocalPort());

            while (true) {
                System.out.print("\nMensagem: ");
                String texto = teclado.readLine();

                if (texto == null || texto.trim().equalsIgnoreCase(SAIR)) {
                    break;
                }

                System.out.print(
                        "Modo [a = automático (N=" + proximo
                                + ") | m = manual]: ");
                String modo = teclado.readLine();

                int nMensagem;
                if (modo != null && modo.trim().equalsIgnoreCase("m")) {
                    System.out.print("Número de sequência N: ");
                    try {
                        String numeroIntroduzido = teclado.readLine();
                        nMensagem = Integer.parseInt(numeroIntroduzido.trim());

                        if (nMensagem <= 0) {
                            System.out.println(
                                    "N inválido: deve ser um inteiro positivo. "
                                            + "Mensagem não enviada.");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("N inválido. Mensagem não enviada.");
                        continue;
                    }
                } else {
                    nMensagem = proximo;
                }

                String mensagem = nMensagem + "," + texto;
                byte[] dadosMensagem =
                        mensagem.getBytes(StandardCharsets.UTF_8);

                DatagramPacket request = new DatagramPacket(
                        dadosMensagem,
                        dadosMensagem.length,
                        aHost,
                        SERVER_PORT);
                aSocket.send(request);
                System.out.println("Enviado : \"" + mensagem + "\"");

                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket reply =
                        new DatagramPacket(buffer, buffer.length);

                try {
                    aSocket.receive(reply);
                } catch (SocketTimeoutException e) {
                    System.out.println(
                            "Sem resposta do servidor (timeout). "
                                    + "Estará em execução?");
                    continue;
                }

                String resposta = new String(
                        reply.getData(),
                        reply.getOffset(),
                        reply.getLength(),
                        StandardCharsets.UTF_8);

                if (resposta.startsWith(PREFIXO_WAITING)) {
                    int esperada = extrairNumero(
                            resposta, PREFIXO_WAITING);

                    if (esperada < 0) {
                        System.out.println(
                                "Resposta inválida do servidor: \""
                                        + resposta + "\"");
                    } else if (nMensagem < esperada) {
                        System.out.println(
                                ">>> DUPLICADA OU ANTIGA: a mensagem "
                                        + nMensagem
                                        + " já tinha sido entregue e foi descartada."
                                        + " O servidor espera a mensagem "
                                        + esperada + ".");
                    } else if (mensagensPendentes.contains(nMensagem)) {
                        System.out.println(
                                ">>> DUPLICADA NO TEMP: já existia uma mensagem "
                                        + nMensagem
                                        + " guardada. Esta segunda cópia foi descartada."
                                        + " O servidor espera a mensagem "
                                        + esperada + ".");
                    } else {
                        mensagensPendentes.add(nMensagem);
                        System.out.println(
                                ">>> FORA DE ORDEM: a mensagem "
                                        + nMensagem
                                        + " ficou guardada temporariamente."
                                        + " O servidor espera a mensagem "
                                        + esperada + ".");
                    }
                } else if (resposta.startsWith(PREFIXO_OK)) {
                    int ultimoEntregue = extrairNumero(resposta, PREFIXO_OK);

                    if (ultimoEntregue < 0) {
                        System.out.println(
                                "Resposta inválida do servidor: \""
                                        + resposta + "\"");
                    } else {
                        System.out.println(
                                "Confirmação: \"" + resposta + "\". "
                                        + "Todas as mensagens até "
                                        + ultimoEntregue
                                        + " foram entregues em ordem.");

                        // A confirmação é cumulativa: a próxima é L + 1.
                        proximo = ultimoEntregue + 1;
                        removerPendentesEntregues(
                                mensagensPendentes, ultimoEntregue);
                    }
                } else {
                    System.out.println(
                            "Resposta desconhecida do servidor: \""
                                    + resposta + "\"");
                }
            }

            System.out.println("Cliente terminado.");

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

    /** Extrai o número situado depois do prefixo da resposta. */
    private static int extrairNumero(String resposta, String prefixo) {
        try {
            return Integer.parseInt(
                    resposta.substring(prefixo.length()).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Retira do conjunto todas as mensagens confirmadas pelo ok,L. */
    private static void removerPendentesEntregues(
            Set<Integer> mensagensPendentes,
            int ultimoEntregue) {

        Iterator<Integer> iterator = mensagensPendentes.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() <= ultimoEntregue) {
                iterator.remove();
            }
        }
    }
}
