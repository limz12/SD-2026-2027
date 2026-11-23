package tpf;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

/**
 * A chave mestra (MK) do lider: 32 bytes aleatorios, guardados em master.key.
 *
 * - No primeiro arranque o ficheiro nao existe: gera-se com SecureRandom e grava-se
 *   com permissoes de leitura/escrita so para o dono (POSIX rw- --- ---; no Windows
 *   vale a restricao da ACL da pasta do utilizador).
 * - Se existir mas nao tiver exatamente 32 bytes, o arranque e recusado.
 * - O ficheiro esta no .gitignore e NUNCA vai para o repositorio.
 */
public final class MasterKey {

    /** Tamanho obrigatorio da chave mestra, em bytes (256 bits, para AES-256). */
    public static final int TAMANHO = 32;

    private final byte[] bytes;

    private MasterKey(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Carrega a master.key do caminho dado, gerando-a se ainda nao existir. */
    public static MasterKey carregar(Path caminho) throws IOException {
        if (Files.isRegularFile(caminho)) {
            byte[] existente = Files.readAllBytes(caminho);
            if (existente.length != TAMANHO) {
                throw new IllegalStateException("A " + caminho.getFileName() + " existe mas tem "
                        + existente.length + " bytes; tem de ter exatamente " + TAMANHO
                        + ". Se perdeu a chave, os chunks cifrados ficaram irrecuperaveis.");
            }
            return new MasterKey(existente);
        }

        // Primeiro arranque: gera 32 bytes aleatorios e grava-os.
        byte[] nova = new byte[TAMANHO];
        new SecureRandom().nextBytes(nova);
        Path pai = caminho.toAbsolutePath().getParent();
        if (pai != null) {
            Files.createDirectories(pai);
        }
        try (var saida = Files.newOutputStream(caminho,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            saida.write(nova);
        }
        restringirPermissoes(caminho);
        return new MasterKey(nova);
    }

    /** Copia defensiva: quem recebe nao pode alterar a chave em memoria. */
    public byte[] copiarBytes() {
        return bytes.clone();
    }

    /**
     * Permissoes de leitura/escrita so para o dono. Em sistemas POSIX
     * (Linux/macOS) usa rw-------. Em Windows nao se reescreve a ACL: reescrever
     * pode retirar o acesso ao proprio dono; por omissao, os ficheiros criados
     * na pasta do utilizador (NTFS) ja ficam inacessiveis a outros utilizadores.
     */
    static void restringirPermissoes(Path ficheiro) {
        try {
            Files.setPosixFilePermissions(ficheiro, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignorada) {
            // Sem POSIX (Windows): a pasta de perfil do utilizador ja restringe o acesso.
            System.out.println("Aviso: " + ficheiro.getFileName()
                    + " criada sem permissoes POSIX (Windows). Garante que a pasta so e acessivel ao dono.");
        }
    }

    /** True se este sistema de ficheiros suporta permissoes POSIX (usado nos testes). */
    static boolean suportaPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
