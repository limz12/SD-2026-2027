package tpf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ReplicaMessageHandler implements PubSubHub.ListenerPublicacaoLider {

    public interface ListenerReplicacao {
        void aoReceberInventario(String idReplica, List<String> chunkIds);

        default void aoReceberHeartbeat(String idReplica) {
        }

        default void aoReceberAck(String idReplica, String chunkId, String sha256) {
        }
    }

    private final ReplicaRegistry replicaRegistry;
    private final Consumer<String> logger;
    private volatile ListenerReplicacao listenerReplicacao;

    public ReplicaMessageHandler(ReplicaRegistry replicaRegistry, Consumer<String> logger) {
        this.replicaRegistry = Objects.requireNonNull(replicaRegistry, "replicaRegistry obrigatório");
        this.logger = (logger != null) ? logger : System.out::println;
    }

    public void setListenerReplicacao(ListenerReplicacao listenerReplicacao) {
        this.listenerReplicacao = listenerReplicacao;
    }

    @Override
    public void aoReceberPublicacao(String idReplicaAutenticado, String topico, String mensagem) {
        if (!"tpf-lider".equals(topico)) {
            logger.accept("ERRO [ReplicaMessageHandler] Tópico inesperado '" + topico + "' de " + idReplicaAutenticado);
            return;
        }
        if (mensagem == null || mensagem.isBlank()) {
            logger.accept("ERRO [ReplicaMessageHandler] Mensagem vazia de " + idReplicaAutenticado);
            return;
        }

        if (mensagem.startsWith("HB;")) {
            tratarHeartbeat(idReplicaAutenticado, mensagem);
        } else if (mensagem.startsWith("INVENTARIO;")) {
            tratarInventario(idReplicaAutenticado, mensagem);
        } else if (mensagem.startsWith("ACK;")) {
            tratarAck(idReplicaAutenticado, mensagem);
        } else {
            logger.accept("ERRO [ReplicaMessageHandler] Comando desconhecido de " + idReplicaAutenticado + ": "
                    + mensagem.substring(0, Math.min(64, mensagem.length())));
        }
    }

    private void tratarHeartbeat(String idAutenticado, String mensagem) {
        String[] partes = mensagem.split(";", -1);
        if (partes.length != 4) {
            logger.accept("ERRO [HB] Formato inválido de " + idAutenticado + " (campos=" + partes.length + ")");
            return;
        }

        String idMsg = partes[1];
        if (!idAutenticado.equals(idMsg)) {
            logger.accept("ERRO [HB] Spoofing detetado: socket=" + idAutenticado + " payload=" + idMsg);
            return;
        }

        long usados;
        long quota;
        try {
            usados = Long.parseLong(partes[2]);
            quota = Long.parseLong(partes[3]);
        } catch (NumberFormatException e) {
            logger.accept("ERRO [HB] Valores numéricos inválidos de " + idAutenticado + ": " + mensagem);
            return;
        }

        if (usados < 0L || quota <= 0L) {
            logger.accept("ERRO [HB] Valores fora de intervalo de " + idAutenticado + ": usados=" + usados + " quota=" + quota);
            return;
        }

        replicaRegistry.onHeartbeat(idAutenticado, usados, quota);
        ListenerReplicacao listener = this.listenerReplicacao;
        if (listener != null) {
            listener.aoReceberHeartbeat(idAutenticado);
        }
    }

    private void tratarInventario(String idAutenticado, String mensagem) {
        String[] partes = mensagem.split(";", -1);
        if (partes.length != 3) {
            logger.accept("ERRO [INVENTARIO] Formato inválido de " + idAutenticado + " (campos=" + partes.length + ")");
            return;
        }

        String idMsg = partes[1];
        if (!idAutenticado.equals(idMsg)) {
            logger.accept("ERRO [INVENTARIO] Spoofing detetado: socket=" + idAutenticado + " payload=" + idMsg);
            return;
        }

        String listaCsv = partes[2];
        List<String> chunkIds;
        if (listaCsv.isEmpty()) {
            chunkIds = Collections.emptyList();
        } else {
            String[] itens = listaCsv.split(",", -1);
            List<String> validos = new ArrayList<>(itens.length);
            for (String item : itens) {
                if (item.isBlank()) {
                    logger.accept("ERRO [INVENTARIO] Entrada vazia na lista de chunks de " + idAutenticado);
                    return;
                }
                validos.add(item);
            }
            chunkIds = Collections.unmodifiableList(validos);
        }

        logger.accept("INVENTARIO id=" + idAutenticado + " chunks=" + chunkIds.size());
        ListenerReplicacao listener = this.listenerReplicacao;
        if (listener != null) {
            listener.aoReceberInventario(idAutenticado, chunkIds);
        }
    }

    private void tratarAck(String idAutenticado, String mensagem) {
        String[] partes = mensagem.split(";", -1);
        if (partes.length != 4) {
            logger.accept("ACK-REJEITADO " + idAutenticado + " formato_invalido");
            return;
        }

        String idMsg = partes[1];
        String chunkId = partes[2];
        String sha256 = partes[3];

        if (!idAutenticado.equals(idMsg)) {
            logger.accept("ACK-REJEITADO " + idAutenticado + " id_divergente=" + idMsg);
            return;
        }

        if (chunkId.isBlank() || sha256.isBlank()) {
            logger.accept("ACK-REJEITADO " + idAutenticado + " campos_vazios");
            return;
        }

        ListenerReplicacao listener = this.listenerReplicacao;
        if (listener != null) {
            listener.aoReceberAck(idAutenticado, chunkId, sha256);
        } else {
            logger.accept("ACK recebido (sem coordenador ativo) id=" + idAutenticado + " chunk=" + chunkId);
        }
    }
}
