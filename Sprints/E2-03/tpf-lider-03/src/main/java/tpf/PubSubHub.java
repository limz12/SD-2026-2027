package tpf;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class PubSubHub extends WebSocketServer {

    public enum FaseLigacao {
        NOVA,
        DESAFIADA,
        AUTENTICADA
    }

    public static class ContextoReplica {
        private final WebSocket conexao;
        private FaseLigacao fase;
        private String idReplica;
        private String nonceB64;
        private PublicKey chavePublicaCandidata;
        private String tokenPendente;

        public ContextoReplica(WebSocket conexao) {
            this.conexao = conexao;
            this.fase = FaseLigacao.NOVA;
        }
    }

    public static class InfoToken {
        private final Instant expiraEm;
        private boolean usado;

        public InfoToken(Instant expiraEm) {
            this.expiraEm = expiraEm;
            this.usado = false;
        }
    }

    @FunctionalInterface
    public interface ListenerPublicacaoLider {
        void aoReceberPublicacao(String idReplicaAutenticado, String topico, String mensagem);
    }

    // Estruturas mínimas exigidas e estado do Hub
    private final Map<String, Set<WebSocket>> subscricoes = new ConcurrentHashMap<>();
    private final Map<String, InfoToken> tokens = new ConcurrentHashMap<>();
    private final Map<WebSocket, ContextoReplica> contextosPorSocket = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> replicasRegistadas = new ConcurrentHashMap<>();

    private final Path ficheiroRegistoReplicas;
    private final ListenerPublicacaoLider listenerLider;
    private final Consumer<String> logger;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object lockGlobalRegisto = new Object();

    public PubSubHub(
            InetSocketAddress endereco,
            Path ficheiroRegistoReplicas,
            ListenerPublicacaoLider listenerLider) {
        this(endereco, ficheiroRegistoReplicas, listenerLider, System.out::println);
    }

    public PubSubHub(
            InetSocketAddress endereco,
            Path ficheiroRegistoReplicas,
            ListenerPublicacaoLider listenerLider,
            Consumer<String> logger) {
        super(Objects.requireNonNull(endereco, "endereco obrigatório"));
        this.ficheiroRegistoReplicas = Objects.requireNonNull(
                ficheiroRegistoReplicas, "ficheiroRegistoReplicas obrigatório");
        this.listenerLider = Objects.requireNonNull(listenerLider, "listenerLider obrigatório");
        this.logger = (logger != null) ? logger : System.out::println;
        setReuseAddr(true);
        carregarReplicasPersistidas();
    }

    /**
     * Gera um token criptograficamente seguro (256 bits) de uso único válido por TOKEN_VALIDADE_MS.
     */
    public String gerarToken() {
        Instant agora = Instant.now();
        // Limpeza oportunista de tokens expirados ou já consumidos
        tokens.entrySet().removeIf(e -> e.getValue().usado || agora.isAfter(e.getValue().expiraEm));

        byte[] bytesAleatorios = new byte[32];
        secureRandom.nextBytes(bytesAleatorios);
        String segredoToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytesAleatorios);

        Instant expiraEm = agora.plusMillis(Wire.TOKEN_VALIDADE_MS);
        tokens.put(segredoToken, new InfoToken(expiraEm));
        return segredoToken;
    }

    /**
     * Publica uma mensagem para todos os clientes autenticados subscritos no tópico.
     */
    public void publish(String topico, String mensagem) {
        if (topico == null || topico.isBlank() || mensagem == null) {
            return;
        }

        Set<WebSocket> destinatarios = subscricoes.get(topico);
        if (destinatarios == null || destinatarios.isEmpty()) {
            return;
        }

        for (WebSocket conn : destinatarios) {
            if (conn == null || !conn.isOpen()) {
                limparLigacao(conn);
                continue;
            }

            ContextoReplica ctx = contextosPorSocket.get(conn);
            if (ctx == null) {
                limparLigacao(conn);
                continue;
            }

            boolean autenticada;
            synchronized (ctx) {
                autenticada = (ctx.fase == FaseLigacao.AUTENTICADA);
            }
            if (!autenticada) {
                limparLigacao(conn);
                continue;
            }

            try {
                conn.send(mensagem);
            } catch (RuntimeException e) {
                logger.accept("ERRO [PubSubHub] Falha ao enviar mensagem no tópico '" + topico + "': " + e.getMessage());
                limparLigacao(conn);
            }
        }
    }

    /**
     * Remove uma réplica registada, reescreve replicas.txt atomicamente e fecha a sua ligação se ativa.
     */
    public boolean removerReplica(String idReplica) {
        if (idReplica == null || idReplica.isBlank()) {
            return false;
        }

        boolean existiaNoRegisto;
        synchronized (lockGlobalRegisto) {
            existiaNoRegisto = (replicasRegistadas.remove(idReplica) != null);
            if (existiaNoRegisto) {
                try {
                    persistirTodasReplicasAtomicamente();
                } catch (IOException e) {
                    logger.accept("ERRO [PubSubHub] Falha ao persistir remoção da réplica '" + idReplica + "': " + e.getMessage());
                }
            }
        }

        boolean desligouSocket = false;
        for (Map.Entry<WebSocket, ContextoReplica> entry : contextosPorSocket.entrySet()) {
            WebSocket conn = entry.getKey();
            ContextoReplica ctx = entry.getValue();
            if (ctx != null) {
                boolean corresponde;
                synchronized (ctx) {
                    corresponde = idReplica.equals(ctx.idReplica);
                }
                if (corresponde) {
                    limparLigacao(conn);
                    if (conn != null && conn.isOpen()) {
                        conn.close(1008, "Replica removida");
                    }
                    desligouSocket = true;
                }
            }
        }

        return existiaNoRegisto || desligouSocket;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (conn == null) {
            return;
        }
        contextosPorSocket.put(conn, new ContextoReplica(conn));
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (conn == null) {
            return;
        }
        ContextoReplica contexto = contextosPorSocket.get(conn);
        if (contexto == null) {
            logger.accept("ERRO [PubSubHub] Mensagem recebida em ligação sem contexto registado");
            conn.close(1008, "Contexto inexistente");
            return;
        }

        if (message == null || message.isEmpty()) {
            rejeitarEFechar(contexto, "Mensagem vazia");
            return;
        }

        if (message.startsWith("OLA;")) {
            processarOla(contexto, message);
        } else if (message.startsWith("PROVA;")) {
            processarProva(contexto, message);
        } else if (message.startsWith("SUB;")) {
            processarSub(contexto, message);
        } else if (message.startsWith("PUB;")) {
            processarPub(contexto, message);
        } else {
            rejeitarEFechar(contexto, "Comando desconhecido ou malformado");
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        if (conn == null) {
            return;
        }
        ContextoReplica contexto = contextosPorSocket.get(conn);
        if (contexto == null) {
            logger.accept("ERRO [PubSubHub] Frame binário recebido em ligação sem contexto registado");
            conn.close(1008, "Contexto inexistente");
            return;
        }
        rejeitarEFechar(contexto, "Frame binário não suportado pelo protocolo");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        limparLigacao(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String detalhe = (ex != null && ex.getMessage() != null) ? ex.getMessage() : "erro_socket";
        logger.accept("ERRO [PubSubHub] Erro na ligação WebSocket: " + detalhe);
        if (conn != null) {
            limparLigacao(conn);
        }
    }

    @Override
    public void onStart() {
        // Servidor pronto a aceitar ligações
    }

    // --- Handlers de Protocolo ---

    private void processarOla(ContextoReplica contexto, String mensagem) {
        String[] partes = mensagem.split(";", -1);

        synchronized (contexto) {
            if (contexto.fase != FaseLigacao.NOVA) {
                rejeitarEFechar(contexto, "OLA recebido fora do estado NOVA (fase=" + contexto.fase + ")");
                return;
            }

            if (partes.length == 4) {
                // 1.º registo: OLA;<id>;<chavePublicaB64>;<token>
                String id = partes[1];
                String chavePubB64 = partes[2];
                String tokenRecebido = partes[3];

                if (!isIdValido(id) || chavePubB64.isBlank() || tokenRecebido.isBlank()) {
                    rejeitarEFechar(contexto, "Campos vazios ou id inválido no OLA de 1.º registo");
                    return;
                }

                if (replicasRegistadas.containsKey(id)) {
                    rejeitarEFechar(contexto, "OLA de 1.º registo recusado: id '" + id + "' já está registado");
                    return;
                }

                PublicKey chavePublica = descodificarChavePublicaEd25519(chavePubB64);
                if (chavePublica == null) {
                    rejeitarEFechar(contexto, "Chave pública Ed25519 X.509 inválida para '" + id + "'");
                    return;
                }

                if (!isTokenDisponivel(tokenRecebido)) {
                    rejeitarEFechar(contexto, "Token inválido, expirado ou já utilizado para '" + id + "'");
                    return;
                }

                String nonceB64 = gerarNonceB64();
                contexto.idReplica = id;
                contexto.chavePublicaCandidata = chavePublica;
                contexto.tokenPendente = tokenRecebido;
                contexto.nonceB64 = nonceB64;
                contexto.fase = FaseLigacao.DESAFIADA;

                enviarDireto(contexto, "DESAFIO;" + nonceB64);

            } else if (partes.length == 2) {
                // Registo seguinte: OLA;<id>
                String id = partes[1];
                if (!isIdValido(id)) {
                    rejeitarEFechar(contexto, "Id inválido no OLA de reconexão");
                    return;
                }

                PublicKey chaveConhecida = replicasRegistadas.get(id);
                if (chaveConhecida == null) {
                    rejeitarEFechar(contexto, "OLA sem token recusado: réplica '" + id + "' não está registada");
                    return;
                }

                String nonceB64 = gerarNonceB64();
                contexto.idReplica = id;
                contexto.chavePublicaCandidata = chaveConhecida;
                contexto.tokenPendente = null;
                contexto.nonceB64 = nonceB64;
                contexto.fase = FaseLigacao.DESAFIADA;

                enviarDireto(contexto, "DESAFIO;" + nonceB64);

            } else {
                rejeitarEFechar(contexto, "Formato de OLA inválido (campos=" + partes.length + ")");
            }
        }
    }

    private void processarProva(ContextoReplica contexto, String mensagem) {
        String[] partes = mensagem.split(";", -1);

        synchronized (contexto) {
            if (contexto.fase != FaseLigacao.DESAFIADA) {
                rejeitarEFechar(contexto, "PROVA recebida sem desafio ativo (fase=" + contexto.fase + ")");
                return;
            }

            if (partes.length != 3) {
                rejeitarEFechar(contexto, "Formato de PROVA inválido (campos=" + partes.length + ")");
                return;
            }

            String idMsg = partes[1];
            String assinaturaB64 = partes[2];

            if (idMsg.isBlank() || assinaturaB64.isBlank()) {
                rejeitarEFechar(contexto, "Campos vazios na PROVA");
                return;
            }

            if (!idMsg.equals(contexto.idReplica)) {
                rejeitarEFechar(contexto, "Id na PROVA não corresponde ao id do OLA");
                return;
            }

            if (!verificarProva(contexto.chavePublicaCandidata, contexto.idReplica, contexto.nonceB64, assinaturaB64)) {
                rejeitarEFechar(contexto, "Assinatura criptográfica inválida na PROVA para '" + contexto.idReplica + "'");
                return;
            }

            if (contexto.tokenPendente != null) {
                boolean registadoComSucesso = efetivarPrimeiroRegisto(
                        contexto.idReplica, contexto.chavePublicaCandidata, contexto.tokenPendente);
                if (!registadoComSucesso) {
                    rejeitarEFechar(contexto, "Falha ao efetivar 1.º registo para '" + contexto.idReplica + "'");
                    return;
                }
            }

            contexto.nonceB64 = null;
            contexto.tokenPendente = null;
            contexto.fase = FaseLigacao.AUTENTICADA;
        }
    }

    private void processarSub(ContextoReplica contexto, String mensagem) {
        String[] partes = mensagem.split(";", -1);

        synchronized (contexto) {
            if (contexto.fase != FaseLigacao.AUTENTICADA) {
                rejeitarEFechar(contexto, "SUB recebido antes de autenticação concluída");
                return;
            }

            if (partes.length != 2 || partes[1].isBlank()) {
                rejeitarEFechar(contexto, "Formato de SUB inválido");
                return;
            }

            String topico = partes[1];
            String topicoPrivadoPermitido = "tpf-r-" + contexto.idReplica;
            if (!topicoPrivadoPermitido.equals(topico) && !"tpf-ctl".equals(topico)) {
                rejeitarEFechar(contexto, "Réplica '" + contexto.idReplica + "' tentou subscrever tópico proibido '" + topico + "'");
                return;
            }

            subscricoes.computeIfAbsent(topico, k -> ConcurrentHashMap.newKeySet()).add(contexto.conexao);
        }
    }

    private void processarPub(ContextoReplica contexto, String mensagem) {
        String[] partes = mensagem.split(";", 3);

        String idAutenticado;
        synchronized (contexto) {
            if (contexto.fase != FaseLigacao.AUTENTICADA) {
                rejeitarEFechar(contexto, "PUB recebido antes de autenticação concluída");
                return;
            }

            if (partes.length != 3 || partes[1].isBlank() || partes[2].isEmpty()) {
                rejeitarEFechar(contexto, "Formato de PUB inválido");
                return;
            }

            String topico = partes[1];
            if (!"tpf-lider".equals(topico)) {
                rejeitarEFechar(contexto, "Réplica '" + contexto.idReplica + "' tentou publicar em tópico proibido '" + topico + "'");
                return;
            }

            idAutenticado = contexto.idReplica;
        }

        listenerLider.aoReceberPublicacao(idAutenticado, partes[1], partes[2]);
    }

    // --- Verificação Criptográfica e Persistência ---

    public boolean verificarProva(PublicKey chavePublica, String idReplica, String nonceB64, String assinaturaB64) {
        if (chavePublica == null || idReplica == null || nonceB64 == null || assinaturaB64 == null) {
            return false;
        }
        try {
            String mensagemAssinada = "tpf-registo|" + idReplica + "|" + nonceB64;
            byte[] dados = mensagemAssinada.getBytes(StandardCharsets.UTF_8);
            byte[] assinatura = Base64.getDecoder().decode(assinaturaB64);

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(chavePublica);
            verifier.update(dados);
            return verifier.verify(assinatura);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isTokenDisponivel(String tokenSegredo) {
        synchronized (lockGlobalRegisto) {
            InfoToken info = tokens.get(tokenSegredo);
            if (info == null) {
                return false;
            }
            if (info.usado || Instant.now().isAfter(info.expiraEm)) {
                return false;
            }
            return true;
        }
    }

    private boolean efetivarPrimeiroRegisto(String idReplica, PublicKey chavePublica, String tokenSegredo) {
        synchronized (lockGlobalRegisto) {
            if (replicasRegistadas.containsKey(idReplica)) {
                return false;
            }

            InfoToken info = tokens.get(tokenSegredo);
            if (info == null || info.usado || Instant.now().isAfter(info.expiraEm)) {
                return false;
            }

            info.usado = true;
            replicasRegistadas.put(idReplica, chavePublica);

            try {
                persistirTodasReplicasAtomicamente();
                return true;
            } catch (IOException e) {
                replicasRegistadas.remove(idReplica);
                info.usado = false;
                logger.accept("ERRO [PubSubHub] Falha ao gravar replicas.txt para '" + idReplica + "': " + e.getMessage());
                return false;
            }
        }
    }

    private void carregarReplicasPersistidas() {
        synchronized (lockGlobalRegisto) {
            if (Files.notExists(ficheiroRegistoReplicas)) {
                return;
            }
            try {
                List<String> linhas = Files.readAllLines(ficheiroRegistoReplicas, StandardCharsets.UTF_8);
                replicasRegistadas.clear();
                for (String linha : linhas) {
                    if (linha == null || linha.isBlank()) {
                        continue;
                    }
                    String[] partes = linha.split(";", -1);
                    if (partes.length != 2 || partes[0].isBlank() || partes[1].isBlank()) {
                        throw new IllegalStateException("Linha inválida em replicas.txt: " + linha);
                    }
                    PublicKey pub = descodificarChavePublicaEd25519(partes[1]);
                    if (pub == null) {
                        throw new IllegalStateException("Chave pública inválida em replicas.txt para id=" + partes[0]);
                    }
                    replicasRegistadas.put(partes[0], pub);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Falha ao carregar replicas.txt no arranque do PubSubHub", e);
            }
        }
    }

    private void persistirTodasReplicasAtomicamente() throws IOException {
        Path diretoriaPai = ficheiroRegistoReplicas.getParent();
        if (diretoriaPai != null && Files.notExists(diretoriaPai)) {
            Files.createDirectories(diretoriaPai);
        }

        List<String> linhas = new ArrayList<>(replicasRegistadas.size());
        for (Map.Entry<String, PublicKey> entry : replicasRegistadas.entrySet()) {
            String chavePubB64 = Base64.getEncoder().encodeToString(entry.getValue().getEncoded());
            linhas.add(entry.getKey() + ";" + chavePubB64);
        }

        Path temporario = ficheiroRegistoReplicas.resolveSibling(
                ficheiroRegistoReplicas.getFileName().toString() + ".tmp");

        Files.write(
                temporario,
                linhas,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        Files.move(
                temporario,
                ficheiroRegistoReplicas,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    // --- Utilitários Internos ---

    private String gerarNonceB64() {
        byte[] nonce = new byte[32];
        secureRandom.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    private PublicKey descodificarChavePublicaEd25519(String chavePubB64) {
        try {
            byte[] derBytes = Base64.getDecoder().decode(chavePubB64);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePublic(new X509EncodedKeySpec(derBytes));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isIdValido(String id) {
        return id != null
                && !id.isBlank()
                && !id.contains(";")
                && !id.contains(",")
                && !id.contains("\n")
                && !id.contains("\r");
    }

    private void enviarDireto(ContextoReplica contexto, String frame) {
        try {
            if (contexto.conexao != null && contexto.conexao.isOpen()) {
                contexto.conexao.send(frame);
            }
        } catch (RuntimeException e) {
            logger.accept("ERRO [PubSubHub] Falha ao enviar frame de controlo: " + e.getMessage());
            limparLigacao(contexto.conexao);
        }
    }

    private void rejeitarEFechar(ContextoReplica contexto, String motivo) {
        logger.accept("ERRO [PubSubHub] " + motivo);
        WebSocket conn = contexto.conexao;
        limparLigacao(conn);
        if (conn != null && conn.isOpen()) {
            conn.close(1008, "Protocolo ou autenticacao invalida");
        }
    }

    private void limparLigacao(WebSocket conn) {
        if (conn == null) {
            return;
        }
        contextosPorSocket.remove(conn);
        for (Set<WebSocket> inscritos : subscricoes.values()) {
            inscritos.remove(conn);
        }
    }
}
