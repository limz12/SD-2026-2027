
package rep02;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

public class PartitionLeader {

    public static void main(String[] args) {

        // Validar argumentos
        if (args.length < 2 || args.length > 4) {
            System.out.println(
                "Uso: PartitionLeader <k> <N> [endereço] [api]"
            );
            return;
        }

        final int k;
        final int N;

        try {
            k = Integer.parseInt(args[0]);
            N = Integer.parseInt(args[1]);

            if (N <= 0 || k < 0 || k >= N) {
                System.out.println(
                    "Erro: N deve ser positivo e 0 <= k < N."
                );
                return;
            }

        } catch (NumberFormatException e) {
            System.out.println(
                "Erro: k e N devem ser inteiros."
            );
            return;
        }

        String endereco = args.length > 2
                ? args[2]
                : "127.0.0.1";

        String api = args.length > 3
                ? args[3]
                : "http://127.0.0.1:5001/api/v0";

        int porto = Wire.PORTO_BASE + k;

        // Particionador com N partições
        final Partitioner partitioner = new Partitioner(N);

        IpfsPubSub ps = new IpfsPubSub(api);

        try {

            RecordFile file = new RecordFile(
                "lider-p" + k + ".txt"
            );

            // Estado partilhado entre as duas threads
            final Object lock = new Object();
            final long[] seq = { file.lastSeq() };

            System.out.println(
                "Líder da partição " + k + "/" + N
                + " preparado."
            );

            System.out.println(
                "Tópico de entrada: " + Wire.topicIn(k)
            );

            System.out.println(
                "Tópico de dados: " + Wire.topicData(k)
            );

            System.out.println(
                "Recuperação TCP: " + endereco + ":" + porto
            );

            System.out.println(
                "Sequência inicial: " + seq[0]
            );

            // THREAD DE ENTRADA
            Thread entrada = new Thread(() -> {

                try {

                    ps.subscribe(Wire.topicIn(k), msg -> {

                        try {

                            // Validar tipo da mensagem
                            if (!Wire.type(msg).equals("IN")) {
                                System.out.println(
                                    "REJEITADO: " + msg
                                );
                                return;
                            }

                            // Formato: IN;sensor;temperatura
                            String[] p = Wire.body(msg).split(
                                ";", -1
                            );

                            if (p.length != 2
                                    || p[0].trim().isEmpty()) {
                                System.out.println(
                                    "REJEITADO: " + msg
                                );
                                return;
                            }

                            String sensor = p[0];
                            double temp = Double.parseDouble(p[1]);

                            if (!Double.isFinite(temp)) {
                                System.out.println(
                                    "REJEITADO: " + msg
                                );
                                return;
                            }

                            // VERIFICAR SE O SENSOR PERTENCE
                            // À PARTIÇÃO DESTE LÍDER
                            int j = partitioner.of(sensor);

                            if (j != k) {
                                System.out.println(
                                    "PARTIÇÃO ERRADA: "
                                    + sensor
                                    + " pertence a p" + j
                                );
                                return;
                            }

                            SensorRecord r;

                            // Secção sincronizada:
                            // atribuição de seq e escrita no ficheiro
                            synchronized (lock) {

                                long proximo = seq[0] + 1;

                                r = SensorRecord.now(
                                    proximo, sensor, temp
                                );

                                file.append(r);

                                seq[0] = proximo;
                            }

                            // Publicação fora da secção sincronizada
                            ps.publish(
                                Wire.topicData(k),
                                Wire.reg(endereco, porto, r)
                            );

                            System.out.println(
                                "Publicado: "
                                + Wire.reg(endereco, porto, r)
                            );

                        } catch (IllegalArgumentException e) {

                            System.out.println(
                                "REJEITADO: " + msg
                            );

                        } catch (IOException e) {

                            System.out.println(
                                "Erro ao guardar/publicar: "
                                + e.getMessage()
                            );

                        } catch (InterruptedException e) {

                            Thread.currentThread().interrupt();
                        }
                    });

                } catch (Exception e) {

                    System.out.println(
                        "Erro na thread de entrada: "
                        + e.getMessage()
                    );
                }

            });

            entrada.setName("entrada-p" + k);

            // CRIAR SERVIDOR TCP ANTES DE INICIAR
            // A THREAD DE ENTRADA
            try (ServerSocket server = new ServerSocket(porto)) {

                System.out.println(
                    "Servidor de recuperação à escuta "
                    + "no porto " + porto
                );

                entrada.start();

                // THREAD PRINCIPAL: RECUPERAÇÃO TCP
                while (true) {

                    try (
                        Socket s = server.accept();

                        BufferedReader in = new BufferedReader(
                            new InputStreamReader(
                                s.getInputStream(),
                                StandardCharsets.UTF_8
                            )
                        );

                        PrintWriter out = new PrintWriter(
                            new OutputStreamWriter(
                                s.getOutputStream(),
                                StandardCharsets.UTF_8
                            ),
                            true
                        )
                    ) {

                        s.setSoTimeout(Wire.T_TCP_MS);

                        String pedido = in.readLine();

                        // Validar formato do pedido
                        if (pedido == null) {
                            out.println("ERRO pedido vazio");
                            continue;
                        }

                        String[] p = pedido.trim().split("\\s+");

                        if (p.length != 3
                                || !p[0].equals("PEDIDO")) {

                            out.println("ERRO pedido mal formado");
                            continue;
                        }

                        long de;
                        long ate;

                        try {
                            de = Long.parseLong(p[1]);
                            ate = Long.parseLong(p[2]);

                        } catch (NumberFormatException e) {

                            out.println("ERRO intervalo inválido");
                            continue;
                        }

                        if (de <= 0 || ate <= 0 || de > ate) {

                            out.println("ERRO intervalo inválido");
                            continue;
                        }

                        // RECUPERAÇÃO
                        int total = 0;
                        List<SensorRecord> registos;

                        // Consultar seq e obter os registos
                        // dentro da secção sincronizada
                        synchronized (lock) {

                            // Limitar ao último seq escrito
                            ate = Math.min(ate, seq[0]);

                            if (de <= ate) {

                                // range já devolve uma lista nova
                                registos = file.range(de, ate);

                            } else {

                                registos = new ArrayList<>();
                            }
                        }

                        // Enviar os registos fora
                        // da secção sincronizada
                        for (SensorRecord r : registos) {

                            out.println(r.toLine());
                            total++;
                        }

                        // Terminar a resposta
                        out.println("FIM");

                        System.out.println(
                            "RECUPERAÇÃO: "
                            + de + ".." + ate
                            + " para "
                            + s.getRemoteSocketAddress()
                            + " (" + total + " registos)"
                        );

                    } catch (IOException e) {

                        System.out.println(
                            "Recuperação interrompida: "
                            + e.getMessage()
                        );
                    }
                }
            }

        } catch (IOException e) {

            System.out.println(
                "IO: " + e.getClass().getSimpleName()
                + ": " + e.getMessage()
            );
        }
    }
}
