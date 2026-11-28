
package tpf;

import java.util.Objects;
import java.util.concurrent.CompletionException;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Arquiva mensagens textuais recebidas
 * no canal #arquivo do servidor configurado.
 *
 * Ignora:
 * - mensagens de bots;
 * - mensagens de webhooks;
 * - mensagens de outros servidores;
 * - mensagens de outros canais;
 * - mensagens sem conteúdo textual relevante.
 *
 * Utiliza a mesma RegistoService
 * que SlashCommandListener.
 *
 * A escrita é executada fora das
 * threads de eventos JDA.
 */
public final class ArquivoChannelListener
        extends ListenerAdapter {

    private final RegistoService registoService;

    private final String guildId;
    private final String canalArquivoId;

    public ArquivoChannelListener(
            RegistoService registoService,
            String guildId,
            String canalArquivoId) {

        this.registoService = Objects.requireNonNull(
            registoService,
            "RegistoService é obrigatório."
        );

        this.guildId = Objects.requireNonNull(
            guildId,
            "Guild ID obrigatório."
        );

        this.canalArquivoId = Objects.requireNonNull(
            canalArquivoId,
            "ID do canal de arquivo obrigatório."
        );
    }

    /**
     * Verifica a origem da mensagem.
     */
    private boolean mensagemAutorizada(
            MessageReceivedEvent event) {

        if (!event.isFromGuild()) {
            return false;
        }

        if (event.getAuthor().isBot()) {
            return false;
        }

        if (event.isWebhookMessage()) {
            return false;
        }

        if (!guildId.equals(
                event.getGuild().getId())) {

            return false;
        }

        return canalArquivoId.equals(
            event.getChannel().getId()
        );
    }

    @Override
    public void onMessageReceived(
            MessageReceivedEvent event) {

        /*
         * Filtrar a origem antes de processar.
         */
        if (!mensagemAutorizada(event)) {
            return;
        }

        /*
         * Preservar o conteúdo original
         * disponibilizado pelo Discord.
         */
        String texto =
                event.getMessage().getContentRaw();

        /*
         * Decisão de validação:
         *
         * Arquivar mensagens que possuam
         * texto não vazio e não composto
         * exclusivamente por espaços.
         *
         * Mensagens apenas com anexos
         * não são arquivadas nesta etapa.
         */
        if (texto.isBlank()) {
            return;
        }

        /*
         * Identificadores numéricos Discord,
         * compatíveis com Registo.
         */
        String autorId =
                event.getAuthor().getId();

        String canalId =
                event.getChannel().getId();

        /*
         * Submissão assíncrona.
         *
         * Não bloquear a thread do JDA
         * com operações de disco.
         */
        registoService.registar(
            autorId,
            canalId,
            texto
        ).whenComplete((registo, erro) -> {

            if (erro != null) {

                Throwable causa = causaReal(erro);

                System.err.println(
                    "ERRO ao arquivar mensagem "
                    + "do canal #arquivo: "
                    + causa.getMessage()
                );

                /*
                 * A escrita não foi confirmada.
                 *
                 * Não repetir automaticamente.
                 */
                event.getChannel()
                    .sendMessage(
                        "Não foi possível confirmar "
                        + "o arquivo da mensagem."
                    )
                    .queue(
                        sucesso -> {
                            // Erro comunicado.
                        },
                        falha -> System.err.println(
                            "Falha ao comunicar erro "
                            + "no Discord: "
                            + falha.getMessage()
                        )
                    );

                return;
            }

            /*
             * Aqui o registo já foi escrito
             * e force(true) terminou com sucesso.
             */
            long lsn = registo.getLsn();

            event.getChannel()
                .sendMessage(
                    "Mensagem arquivada. "
                    + "**LSN: " + lsn + "**"
                )
                .queue(

                    sucesso -> {
                        // Confirmação enviada.
                    },

                    falha -> {

                        /*
                         * O registo continua
                         * persistido, apesar da
                         * falha de confirmação.
                         */
                        System.err.println(
                            "Mensagem persistida "
                            + "com LSN=" + lsn
                            + ", mas a confirmação "
                            + "Discord falhou: "
                            + falha.getMessage()
                        );
                    }
                );
        });
    }

    /**
     * Obtém a causa de uma falha assíncrona.
     */
    private static Throwable causaReal(
            Throwable erro) {

        Throwable atual = erro;

        while (atual instanceof CompletionException
                && atual.getCause() != null) {

            atual = atual.getCause();
        }

        return atual;
    }
}
