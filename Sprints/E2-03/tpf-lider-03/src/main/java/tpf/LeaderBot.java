
package tpf;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.Set;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

public final class LeaderBot {

    private static final String DATA_DIR_PROPERTY =
            "tpf.dataDir";

    private static final String NOME_CANAL_ARQUIVO =
            "arquivo";

    private static final Set<String> PROPRIEDADES_PERMITIDAS =
            Set.of(
                "tpf.dataDir",
                "tpf.chunkMaxBytes",
                "tpf.padBytes",
                "tpf.selagemMs",
                "tpf.porto",
                "tpf.r",
                "tpf.wMin",
                "tpf.alfa",
                "tpf.margemBytes",
                "tpf.periodoHbMs",
                "tpf.suspeitaMs",
                "tpf.mortaMs",
                "tpf.ackTimeoutMs",
                "tpf.maxRondas",
                "tpf.tokenValidadeMs",
                "tpf.backoffMinMs",
                "tpf.backoffMaxMs"
            );

    private LeaderBot() {
    }

    /**
     * Verifica se existem propriedades tpf.*
     * desconhecidas.
     */
    private static void validarNomesPropriedades() {

        for (String nome :
                System.getProperties().stringPropertyNames()) {

            if (nome.startsWith("tpf.")
                    && !PROPRIEDADES_PERMITIDAS.contains(nome)) {

                throw new IllegalArgumentException(
                    "Propriedade JVM desconhecida: "
                    + nome
                );
            }
        }
    }

    /**
     * Força a inicialização e validação
     * da configuração Wire.
     */
    private static void validarWire() {

        int chunkMax = Wire.CHUNK_MAX_BYTES;
        int padBytes = Wire.PAD_BYTES;
        long selagemMs = Wire.SELAGEM_MS;

        if (chunkMax <= 0
                || padBytes <= 0
                || selagemMs <= 0) {

            throw new IllegalArgumentException(
                "Configuração Wire inválida."
            );
        }

        if (Wire.PORTO < 1 || Wire.PORTO > 65535) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: tpf.porto="
                + Wire.PORTO
                + " (esperado 1..65535)"
            );
        }

        if (Wire.W_MIN > Wire.R) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: tpf.wMin="
                + Wire.W_MIN
                + " não pode exceder tpf.r=" + Wire.R
            );
        }

        if (Wire.SUSPEITA_MS >= Wire.MORTA_MS) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: tpf.suspeitaMs="
                + Wire.SUSPEITA_MS
                + " deve ser < tpf.mortaMs=" + Wire.MORTA_MS
            );
        }

        if (Wire.BACKOFF_MIN_MS > Wire.BACKOFF_MAX_MS) {
            throw new IllegalArgumentException(
                "Propriedade JVM inválida: tpf.backoffMinMs="
                + Wire.BACKOFF_MIN_MS
                + " deve ser <= tpf.backoffMaxMs=" + Wire.BACKOFF_MAX_MS
            );
        }
    }

    /**
     * Resolve a pasta persistente de dados.
     *
     * A configuração é obrigatória
     * e deve conter um caminho absoluto.
     */
    private static Path resolverPastaDados() {

        String valor =
                System.getProperty(DATA_DIR_PROPERTY);

        if (valor == null || valor.isBlank()) {

            throw new IllegalArgumentException(
                "É obrigatório definir -D"
                + DATA_DIR_PROPERTY
                + " com um caminho absoluto."
            );
        }

        Path pasta = Path.of(valor).normalize();

        if (!pasta.isAbsolute()) {

            throw new IllegalArgumentException(
                "tpf.dataDir deve conter "
                + "um caminho absoluto."
            );
        }

        return pasta;
    }

    /**
     * Encontra o único canal de texto
     * denominado arquivo no servidor configurado.
     *
     * O ID do canal é utilizado posteriormente
     * pelos listeners.
     */
    private static TextChannel procurarCanalArquivo(
            Guild guild) {

        List<TextChannel> canais =
                guild.getTextChannelsByName(
                    NOME_CANAL_ARQUIVO,
                    true
                );

        if (canais.size() != 1) {

            throw new IllegalStateException(
                "É necessário existir exatamente "
                + "um canal de texto #arquivo "
                + "no servidor configurado. "
                + "Encontrados: " + canais.size()
            );
        }

        return canais.get(0);
    }

    /** Não gerar uma chave nova se já existirem dados que dependam da anterior. */
    static void verificarChaveAusente(Path dados) throws IOException {
        Path chave = dados.resolve("master.key");
        if (!Files.notExists(chave, LinkOption.NOFOLLOW_LINKS)) return;
        Path log = dados.resolve("registos.log");
        Path eventos = dados.resolve("eventos-lider.txt");
        Path selados = dados.resolve("selados");
        boolean existemDados = (Files.exists(log) && Files.size(log) > 0)
                || (Files.exists(eventos) && Files.size(eventos) > 0);
        if (Files.isDirectory(selados)) {
            try (var ficheiros = Files.list(selados)) { existemDados |= ficheiros.findAny().isPresent(); }
        }
        if (existemDados) throw new IOException("master.key ausente numa pasta com dados; arranque recusado");
    }

    /**
     * Apresenta erros de inicialização
     * sem expor a chave mestra.
     */
    private static void apresentarErro(
            String contexto,
            Throwable erro) {

        System.err.println(
            "ERRO: " + contexto
            + ": " + erro.getMessage()
        );

        for (Throwable extra : erro.getSuppressed()) {

            System.err.println(
                "ERRO ADICIONAL: "
                + extra.getMessage()
            );
        }
    }

    private static int executar() {

        JDA jda = null;
        RegistoLog registoLog = null;
        RegistoService registoService = null;
        ReplicaRegistry replicaRegistry = null;
        Catalog catalog = null;
        PubSubHub hub = null;
        ReplicationManager replicationManager = null;

        boolean inicializado = false;

        try {

            /*
             * 1. Validar configuração.
             */
            validarNomesPropriedades();
            validarWire();

            Path pastaDados = resolverPastaDados();

            /*
             * 2. Validar ou criar a chave mestra.
             *
             * Mantida para os passos posteriores
             * de cifra e derivação de DEKs.
             */
            verificarChaveAusente(pastaDados);
            MasterKey masterKey =
                    MasterKey.carregarOuCriar(
                        pastaDados.resolve("master.key")
                    );

            /*
             * 3. Recuperar o log existente.
             *
             * Um ficheiro inválido impede
             * o arranque.
             */
            registoLog = new RegistoLog(
                pastaDados.resolve("registos.log")
            );

            /*
             * 4. Criar o serviço comum.
             *
             * Ambos os listeners utilizarão
             * esta mesma instância.
             */
            registoService =
                    new RegistoService(registoLog, masterKey, pastaDados);

            /*
             * 4b. Criar registry, catálogo, hub PubSub e gestor de replicação.
             */
            Path ficheiroEventosLider = pastaDados.resolve("eventos-lider.txt");
            java.util.function.Consumer<String> loggerLider = msg -> {
                String linha = java.time.Instant.now() + " " + msg;
                System.out.println(linha);
                try {
                    Files.writeString(
                            ficheiroEventosLider,
                            linha + System.lineSeparator(),
                            java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND
                    );
                } catch (IOException e) {
                    System.err.println("ERRO ao escrever eventos-lider.txt: " + e.getMessage());
                }
            };

            replicaRegistry = new ReplicaRegistry(loggerLider);
            catalog = new Catalog(pastaDados.resolve("catalogo.txt"), loggerLider);
            ReplicaMessageHandler messageHandler = new ReplicaMessageHandler(replicaRegistry, loggerLider);

            hub = new PubSubHub(
                    new InetSocketAddress(Wire.PORTO),
                    pastaDados.resolve("replicas.txt"),
                    messageHandler,
                    loggerLider
            );

            replicationManager = new ReplicationManager(
                    replicaRegistry,
                    catalog,
                    hub,
                    pastaDados,
                    loggerLider
            );
            messageHandler.setListenerReplicacao(replicationManager);
            registoService.setListenerChunkSelado(replicationManager::onChunkSelado);

            System.out.println(
                "Chave mestra inicializada."
            );

            System.out.println(
                "Último LSN recuperado: "
                + registoService.getUltimoLsn()
            );

            /*
             * 5. Ler configuração Discord.
             */
            String token = EnvConfig.get("BOT_TOKEN");
            String guildId = EnvConfig.get("GUILD_ID");

            /*
             * 6. Iniciar JDA sem listeners.
             *
             * Assim, não são aceites pedidos
             * enquanto o canal ainda não
             * estiver identificado.
             */
            jda = JDABuilder.createDefault(token)
                    .enableIntents(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT
                    )
                    .build();

            jda.awaitReady();

            Guild guild = jda.getGuildById(guildId);

            if (guild == null) {

                throw new IllegalStateException(
                    "Servidor Discord não encontrado: "
                    + guildId
                );
            }

            /*
             * 7. Identificar o canal
             * pelo ID no servidor correto.
             */
            TextChannel arquivo =
                    procurarCanalArquivo(guild);

            String canalArquivoId = arquivo.getId();

            /*
             * 8. Injetar o mesmo serviço
             * nos dois pontos de entrada.
             */
            SlashCommandListener slashListener =
                    new SlashCommandListener(
                        registoService,
                        hub,
                        replicaRegistry,
                        catalog,
                        guildId,
                        canalArquivoId
                    );

            ArquivoChannelListener arquivoListener =
                    new ArquivoChannelListener(
                        registoService,
                        guildId,
                        canalArquivoId
                    );

            jda.addEventListener(
                slashListener,
                arquivoListener
            );

            /*
             * 9. Registar os comandos.
             *
             * complete() permite detetar
             * falhas de configuração durante
             * o arranque.
             */
            guild.updateCommands()
                    .addCommands(
                        SlashCommandListener.comandos()
                    )
                    .complete();

            hub.start();
            System.out.println(
                "PubSubHub a escutar no porto " + Wire.PORTO
            );

            replicationManager.verificarChunksSubreplicados();

            /*
             * 10. Preparar encerramento ordenado: registry -> replicationManager -> hub -> registoService.
             */
            final JDA botFinal = jda;
            final ReplicaRegistry registryFinal = replicaRegistry;
            final ReplicationManager repManagerFinal = replicationManager;
            final PubSubHub hubFinal = hub;
            final RegistoService serviceFinal = registoService;

            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {

                    botFinal.shutdown();
                    registryFinal.close();
                    repManagerFinal.close();

                    try {
                        hubFinal.stop(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        apresentarErro(
                            "Encerramento do PubSubHub interrompido",
                            e
                        );
                    }

                    try {
                        serviceFinal.close();
                    } catch (IOException e) {
                        apresentarErro(
                            "Falha no encerramento "
                            + "do serviço de registo",
                            e
                        );
                    }

                }, "encerramento-lider")
            );

            System.out.println(
                "LeaderBot online no servidor: "
                + guild.getName()
            );

            System.out.println(
                "Canal de arquivo: #"
                + arquivo.getName()
                + " (ID: " + canalArquivoId + ")"
            );

            System.out.println(
                "Serviço de registo durável pronto."
            );

            inicializado = true;
            return 0;

        } catch (IOException e) {

            apresentarErro(
                "Falha de inicialização dos ficheiros",
                e
            );

        } catch (ExceptionInInitializerError e) {

            apresentarErro(
                "Falha na inicialização da configuração",
                e.getCause() != null
                    ? e.getCause()
                    : e
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            apresentarErro(
                "Arranque interrompido",
                e
            );

        } catch (RuntimeException e) {

            apresentarErro(
                "Falha no arranque do líder",
                e
            );

        } finally {

            if (!inicializado) {

                if (jda != null) {
                    jda.shutdown();
                }

                if (replicaRegistry != null) {
                    replicaRegistry.close();
                }

                if (replicationManager != null) {
                    replicationManager.close();
                }

                if (hub != null) {
                    try {
                        hub.stop(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        apresentarErro(
                            "Falha ao fechar PubSubHub",
                            e
                        );
                    }
                }

                if (registoService != null) {

                    try {

                        registoService.close();

                    } catch (IOException e) {

                        apresentarErro(
                            "Falha ao fechar "
                            + "RegistoService",
                            e
                        );
                    }

                } else if (registoLog != null) {

                    try {

                        registoLog.close();

                    } catch (IOException e) {

                        apresentarErro(
                            "Falha ao fechar registos.log",
                            e
                        );
                    }
                }
            }
        }

        return 1;
    }

    public static void main(String[] args) {

        int resultado = executar();

        if (resultado != 0) {
            System.exit(resultado);
        }
    }
}
