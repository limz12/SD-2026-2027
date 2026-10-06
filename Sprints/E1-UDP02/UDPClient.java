import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Cliente UDP com numeração automática ou manual.
 *
 * Respostas reconhecidas:
 * - ok,L: todas as mensagens até L foram entregues;
 * - waitingfor,N: mensagem nova guardada fora de ordem;
 * - duplicada,waitingfor,N: segunda cópia descartada;
 * - antiga,waitingfor,N: mensagem já entregue anteriormente.
 */
public class UDPClient {

    private static final String SAIR = "fim";
    private static final String PREFIXO_OK = "ok,";
    private static final String PREFIXO_WAITING = "waitingfor,";
    private static final String PREFIXO_DUPLICADA = "duplicada,";
    private static final String PREFIXO_ANTIGA = "antiga,";

    private static final int SERVER_PORT = 6789;
    private static final int BUFFER_SIZE = 1000;
    private static final int TIMEOUT = 5000;

    public static void main(String[] args) {
        DatagramSocket socket = null;
        BufferedReader teclado = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        // Próximo número sugerido no modo automático.
        int proximo = 1;

        try {
            /*
             * O sistema operativo escolhe um porto de origem livre.
             * O servidor usa IP + este porto para identificar o cliente.
             */
            socket = new DatagramSocket();
            socket.setSoTimeout(TIMEOUT);

            InetAddress servidor = InetAddress.getByName("localhost");

            System.out.println(
                    "Cliente UDP. Escreva \"" + SAIR + "\" para terminar.");
            System.out.println("Porto local: " + socket.getLocalPort());

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

                int N;

                if (modo != null && modo.trim().equalsIgnoreCase("m")) {
                    System.out.print("Número de sequência N: ");

                    try {
                        N = Integer.parseInt(teclado.readLine().trim());

                        if (N < 1) {
                            System.out.println(
                                    "N inválido. Deve ser um inteiro positivo.");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println(
                                "N inválido. Mensagem não enviada.");
                        continue;
                    }
                } else {
                    N = proximo;
                }

                String mensagem = N + "," + texto;
                byte[] dados = mensagem.getBytes(StandardCharsets.UTF_8);

                DatagramPacket request = new DatagramPacket(
                        dados,
                        dados.length,
                        servidor,
                        SERVER_PORT);
                socket.send(request);

                System.out.println("Enviado: \"" + mensagem + "\"");

                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket reply =
                        new DatagramPacket(buffer, buffer.length);

                try {
                    // Bloqueia até chegar uma resposta ou terminar o timeout.
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    System.out.println(
                            "Sem resposta do servidor. Estará em execução?");
                    continue;
                }

                String resposta = new String(
                        reply.getData(),
                        reply.getOffset(),
                        reply.getLength(),
                        StandardCharsets.UTF_8);

                if (resposta.startsWith(PREFIXO_OK)) {
                    int L = extrairUltimoNumero(resposta);

                    if (L < 0) {
                        System.out.println(
                                "Resposta inválida: \"" + resposta + "\"");
                    } else {
                        // ok,L é cumulativo: a próxima mensagem será L+1.
                        proximo = L + 1;
                        System.out.println(
                                ">>> ENTREGUE: todas as mensagens até "
                                        + L + " foram entregues.");
                    }
                } else if (resposta.startsWith(PREFIXO_DUPLICADA)) {
                    int esperada = extrairUltimoNumero(resposta);
                    System.out.println(
                            ">>> DUPLICADA: a segunda cópia da mensagem "
                                    + N + " foi descartada. O servidor espera a "
                                    + esperada + ".");
                } else if (resposta.startsWith(PREFIXO_ANTIGA)) {
                    int esperada = extrairUltimoNumero(resposta);
                    System.out.println(
                            ">>> ANTIGA: a mensagem " + N
                                    + " já tinha sido entregue. O servidor espera a "
                                    + esperada + ".");
                } else if (resposta.startsWith(PREFIXO_WAITING)) {
                    int esperada = extrairUltimoNumero(resposta);
                    System.out.println(
                            ">>> FORA DE ORDEM: a mensagem " + N
                                    + " ficou guardada. O servidor espera a "
                                    + esperada + ".");
                } else {
                    System.out.println(
                            "Resposta desconhecida: \"" + resposta + "\"");
                }
            }

            System.out.println("Cliente terminado.");

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

    /** Extrai o último número de uma resposta separada por vírgulas. */
    private static int extrairUltimoNumero(String resposta) {
        try {
            int ultimaVirgula = resposta.lastIndexOf(',');
            return Integer.parseInt(
                    resposta.substring(ultimaVirgula + 1).trim());
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }
}
