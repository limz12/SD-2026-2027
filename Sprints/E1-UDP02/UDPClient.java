import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;

/**
 * Cliente UDP.
 * - lê mensagens do teclado, várias por execução, termina com "fim";
 * - modo automático (a): numera sozinho 1, 2, 3, ...
 * - modo manual (m): o utilizador escolhe N (necessário para provocar
 * desordenação);
 * - distingue um echo de uma resposta "waitingfor,<L+1>".
 */
public class UDPClient {

    private static final String SAIR = "fim";
    private static final String PREFIXO_WAITING = "waitingfor,";

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

        // Próximo número a usar em modo automático.
        int proximo = 1;

        try {
            // Socket sem porto indicado: o SO atribui um porto efémero livre.
            // O cliente não precisa de porto conhecido — é ele que inicia o diálogo.
            aSocket = new DatagramSocket();

            // Se o servidor estiver desligado, receive() bloquearia para sempre.
            // O timeout evita isso (o UDP não avisa que não há ninguém do outro lado).
            aSocket.setSoTimeout(5000);

            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;

            System.out.println("Cliente UDP. Escreva \"" + SAIR + "\" para terminar.");

            while (true) {

                System.out.print("\nMensagem: ");
                String texto = teclado.readLine();
                if (texto == null || texto.trim().equalsIgnoreCase(SAIR))
                    break;

                System.out.print("Modo [a = automático (N=" + proximo + ") | m = manual]: ");
                String modo = teclado.readLine();

                int N;
                if (modo != null && modo.trim().equalsIgnoreCase("m")) {
                    System.out.print("Número de sequência N: ");
                    try {
                        N = Integer.parseInt(teclado.readLine().trim());
                    } catch (NumberFormatException e) {
                        System.out.println("N inválido. Mensagem não enviada.");
                        continue;
                    }
                } else {
                    N = proximo;
                }

                String mensagem = N + "," + texto;

                byte[] m = mensagem.getBytes();
                DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
                aSocket.send(request);
                System.out.println("Enviado : \"" + mensagem + "\"");

                // Buffer novo a cada iteração, pelo mesmo motivo que no servidor.
                byte[] buffer = new byte[1000];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);

                try {
                    aSocket.receive(reply);
                } catch (SocketTimeoutException e) {
                    System.out.println("Sem resposta do servidor (timeout). Estará em execução?");
                    continue;
                }

                // getLength() em vez de getData() inteiro: caso contrário apareceriam
                // os bytes residuais do buffer depois do texto útil.
                String resposta = new String(reply.getData(), 0, reply.getLength());

                // Um echo é sempre "N,texto", logo começa por dígito;
                // um pedido de retransmissão começa por 'w'. Não há ambiguidade,
                // mesmo que o utilizador escreva "waitingfor,2" como texto.
                if (resposta.startsWith(PREFIXO_WAITING)) {
                    String esperada = resposta.substring(PREFIXO_WAITING.length());
                    //System.out.println(">>> FORA DE ORDEM: o servidor está à espera da mensagem " + esperada);
                    System.out.println(">>> FORA DE ORDEM ");
                } else {
                    System.out.println("Echo    : \"" + resposta + "\"  (aceite em ordem)");
                    proximo = N + 1; // sincroniza a numeração automática com o que foi aceite
                }
            }

            System.out.println("Cliente terminado.");

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