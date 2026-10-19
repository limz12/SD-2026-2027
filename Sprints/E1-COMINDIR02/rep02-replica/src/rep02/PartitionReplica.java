
package rep02;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PartitionReplica {

    static class Estado {

        final int k;
        final RecordFile file;

        long seq;
        TreeMap<Long, SensorRecord> retidos = new TreeMap<>();

        String host;
        int porto;

        // Métricas por partição
        long ligacoesRecuperacao = 0;
        long registosTCP = 0;
        long ultimaEscrita = 0;

        // Controlo de descarte por partição
        boolean jaDescartado = false;

        Estado(int k, String id) throws IOException {
            this.k = k;
            this.file = new RecordFile(
                "replica-" + id + "-p" + k + ".txt"
            );
            this.seq = file.lastSeq();
        }

        // Escreve um registo e atualiza a sequência
        void escrever(SensorRecord r) throws IOException {

            file.append(r);
            seq = r.getSeq();

            // Métrica: instante da última escrita
            ultimaEscrita = System.currentTimeMillis();
        }

        // Escreve os registos retidos consecutivos
        void descarregarRetidos() throws IOException {

            while (retidos.containsKey(seq + 1)) {

                SensorRecord r = retidos.get(seq + 1);

                escrever(r);

                retidos.remove(r.getSeq());
            }
        }

        // Aplica a regra de ordenação dos registos
        void aplicar(SensorRecord r) throws IOException {

            long n = r.getSeq();

            if (n <= seq) {

                System.out.println(
                    "DUPLICADO: " + n
                );

            } else if (retidos.containsKey(n)) {

                System.out.println(
                    "DUPLICADO: " + n
                );

            } else if (n == seq + 1) {

                escrever(r);
                descarregarRetidos();

            } else {

                retidos.put(n, r);

                System.out.println(
                    "EM ESPERA: " + n
                );
            }
        }

        // Recuperação TCP na thread de subscrição
        void recuperar() {

            if (host == null || porto <= 0) {

                System.out.println(
                    "RECUPERAÇÃO FALHOU: líder desconhecido"
                );
                return;
            }

            if (retidos.isEmpty()) {
                return;
            }

            long de = seq + 1;

            // Menor número retido menos 1
            long ate = retidos.firstKey() - 1;

            if (de > ate) {
                return;
            }

            System.out.println(
                "RECUPERAÇÃO: " + de + ".." + ate
                + " pedida a " + host + ":" + porto
            );

            try (Socket s = new Socket()) {

                s.connect(
                    new InetSocketAddress(host, porto),
                    Wire.T_TCP_MS
                );

                // Métrica: ligação TCP estabelecida
                ligacoesRecuperacao++;

                s.setSoTimeout(Wire.T_TCP_MS);

                try (
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

                    out.println("PEDIDO " + de + " " + ate);

                    if (out.checkError()) {
                        throw new IOException(
                            "Falha ao enviar pedido"
                        );
                    }

                    String linha;
                    boolean fim = false;

                    while ((linha = in.readLine()) != null) {

                        if (linha.equals("FIM")) {
                            fim = true;
                            break;
                        }

                        if (linha.startsWith("ERRO")) {
                            throw new IOException(linha);
                        }

                        SensorRecord r;

                        try {
                            r = SensorRecord.fromLine(linha);

                        } catch (RuntimeException e) {

                            throw new IOException(
                                "Registo inválido: " + linha, e
                            );
                        }

                        if (r.getSeq() < de || r.getSeq() > ate) {

                            throw new IOException(
                                "Sequência fora do intervalo: "
                                + r.getSeq()
                            );
                        }

                        // Métrica: registo recebido por TCP
                        registosTCP++;

                        // Aplica sem simulação de perdas
                        aplicar(r);

                        System.out.println(
                            "RECUPERADO: " + r.getSeq()
                        );
                    }

                    if (!fim) {

                        throw new IOException(
                            "Ligação terminou sem FIM"
                        );
                    }
                }

                // Aplicar os retidos que ficaram consecutivos
                descarregarRetidos();

            } catch (IOException e) {

                System.out.println(
                    "RECUPERAÇÃO FALHOU: "
                    + e.getMessage()
                );
            }
        }

        // Processa mensagens recebidas pelo PubSub
        void receber(
                String msg,
                double perda,
                long descartar) {

            try {

                if (!Wire.type(msg).equals("REG")) {

                    System.out.println(
                        "REJEITADO: " + msg
                    );
                    return;
                }

                String[] partes = Wire.regParts(msg);

                String novoHost = partes[0];
                int novoPorto = Integer.parseInt(partes[1]);

                if (novoHost.isBlank()
                        || novoPorto <= 0
                        || novoPorto > 65535) {

                    throw new IllegalArgumentException(
                        "Endereço ou porto inválido"
                    );
                }

                SensorRecord r = SensorRecord.fromLine(
                    partes[2]
                );

                // Atualizar endereço e porto do líder
                host = novoHost;
                porto = novoPorto;

                long n = r.getSeq();

                // Descartar a primeira receção da sequência
                if (n == descartar && !jaDescartado) {

                    jaDescartado = true;

                    System.out.println(
                        "DESCARTADO (simulação): " + n
                    );
                    return;
                }

                // Simulação de perda aleatória
                if (Math.random() < perda) {

                    System.out.println(
                        "DESCARTADO (simulação): " + n
                    );
                    return;
                }

                // Aplicar registo recebido
                aplicar(r);

                // Recuperação na própria thread
                if (retidos.size() > Wire.LIMITE_RETIDOS) {
                    recuperar();
                }

            } catch (IllegalArgumentException e) {

                System.out.println(
                    "REJEITADO: " + msg
                );

            } catch (IOException e) {

                System.out.println(
                    "Erro na réplica da partição "
                    + k + ": " + e.getMessage()
                );
            }
        }

        // Apresenta as métricas da partição
        void mostrarResumo() {

            System.out.println(
                "----- RESUMO PARTIÇÃO " + k + " -----"
            );

            System.out.println(
                "Última sequência escrita: " + seq
            );

            System.out.println(
                "Ligações de recuperação abertas: "
                + ligacoesRecuperacao
            );

            System.out.println(
                "Registos recebidos por TCP: "
                + registosTCP
            );

            System.out.println(
                "Instante da última escrita: "
                + ultimaEscrita + " ms"
            );

            System.out.println(
                "Retidos no fim/consulta: "
                + retidos.size()
            );

            System.out.println(
                "--------------------------------"
            );
        }
    }

    public static void main(String[] args) {

        // Argumentos: <id> <partições> [portoApi]
        if (args.length < 2 || args.length > 3) {

            System.out.println(
                "Uso: PartitionReplica <id> <partições> [portoApi]"
            );
            return;
        }

        String id = args[0];

        if (id.isBlank()) {

            System.out.println("Erro: id inválido.");
            return;
        }

        int portoApi = 5001;

        try {

            if (args.length > 2) {
                portoApi = Integer.parseInt(args[2]);
            }

            if (portoApi <= 0 || portoApi > 65535) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {

            System.out.println(
                "Erro: portoApi inválido."
            );
            return;
        }

        String api = "http://127.0.0.1:"
                + portoApi + "/api/v0";

        // Propriedades de simulação
        final double perda;
        final long descartar;

        try {

            perda = Double.parseDouble(
                System.getProperty("perda", "0")
            );

            descartar = Long.parseLong(
                System.getProperty("descartar", "-1")
            );

            if (!Double.isFinite(perda)
                    || perda < 0 || perda > 1
                    || descartar < -1) {

                throw new IllegalArgumentException();
            }

        } catch (IllegalArgumentException e) {

            System.out.println(
                "Erro: perda deve estar entre 0 e 1 "
                + "e descartar deve ser positivo."
            );
            return;
        }

        // Interpretar lista de partições
        String[] lista = args[1].split(",", -1);

        Set<Integer> particoes = new LinkedHashSet<>();

        try {

            for (String elemento : lista) {

                int k = Integer.parseInt(
                    elemento.trim()
                );

                if (k < 0) {
                    throw new NumberFormatException();
                }

                if (!particoes.add(k)) {
                    throw new IllegalArgumentException(
                        "Partição repetida: " + k
                    );
                }
            }

        } catch (NumberFormatException e) {

            System.out.println(
                "Erro: lista de partições inválida."
            );
            return;

        } catch (IllegalArgumentException e) {

            System.out.println(
                "Erro: " + e.getMessage()
            );
            return;
        }

        // Criar estados independentes
        List<Estado> estados = new ArrayList<>();

        try {

            for (int k : particoes) {
                estados.add(new Estado(k, id));
            }

        } catch (IOException e) {

            System.out.println(
                "Erro ao abrir ficheiros: "
                + e.getMessage()
            );
            return;
        }

        System.out.println(
            "Réplica " + id + " iniciada."
        );

        System.out.println(
            "Partições: " + particoes
        );

        System.out.println(
            "API: " + api
        );

        System.out.println(
            "Perda simulada: " + perda
        );

        System.out.println(
            "Descartar sequência: " + descartar
        );

        // Iniciar uma thread por partição
        for (Estado estado : estados) {

            Thread t = new Thread(() -> {

                IpfsPubSub ps = new IpfsPubSub(api);

                try {

                    ps.subscribe(
                        Wire.topicData(estado.k),
                        msg -> {
                            synchronized (estado) {
                                estado.receber(
                                    msg,
                                    perda,
                                    descartar
                                );
                            }
                        }
                    );

                } catch (Exception e) {

                    System.out.println(
                        "Erro na subscrição da partição "
                        + estado.k + ": " + e.getMessage()
                    );
                }

            });

            t.setName("replica-" + id + "-p" + estado.k);
            t.start();

            System.out.println(
                "Subscrição iniciada: "
                + Wire.topicData(estado.k)
                + " (seq atual = " + estado.seq + ")"
            );
        }

        // Permitir consultar as métricas durante a execução
        try (Scanner sc = new Scanner(System.in)) {

            System.out.println(
                "Comandos: resumo | sair"
            );

            while (true) {

                System.out.print("> ");

                if (!sc.hasNextLine()) {
                    break;
                }

                String comando = sc.nextLine().trim();

                if (comando.equalsIgnoreCase("resumo")) {

                    for (Estado estado : estados) {

                        synchronized (estado) {
                            estado.mostrarResumo();
                        }
                    }

                } else if (comando.equalsIgnoreCase("sair")) {

                    System.out.println("Resumo final:");

                    for (Estado estado : estados) {

                        synchronized (estado) {
                            estado.mostrarResumo();
                        }
                    }

                    System.exit(0);

                } else {

                    System.out.println(
                        "Comando inválido. Use: resumo | sair"
                    );
                }
            }
        }
    }
}
