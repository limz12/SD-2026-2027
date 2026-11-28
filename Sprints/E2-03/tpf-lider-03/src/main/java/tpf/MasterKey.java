
package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Gere a chave mestra persistente do líder TPF.
 *
 * A chave tem exatamente 32 bytes.
 *
 * Se não existir, é criada com SecureRandom.
 * Se existir, é reutilizada sem alterações.
 *
 * Não substitui chaves inválidas.
 * Não imprime nem regista os bytes da chave.
 * Não implementa derivação de DEKs nem cifra.
 *
 * Requisitos operacionais:
 * - caminho absoluto;
 * - diretório controlado pelo utilizador do líder;
 * - sem arranques concorrentes de processos independentes;
 * - sistema de ficheiros com permissões POSIX ou ACL.
 *
 * A validação de caminhos não elimina todas as
 * condições de corrida com processos externos.
 */
public final class MasterKey {

    public static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private static final Set<PosixFilePermission> POSIX_PERMISSIONS =
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            );

    /*
     * Permissões permitidas na entrada ACL do dono.
     *
     * Nenhuma outra entidade deve receber
     * acesso através da ACL do ficheiro.
     */
    private static final Set<AclEntryPermission> OWNER_ACL_PERMISSIONS =
            EnumSet.of(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.SYNCHRONIZE
            );

    private final byte[] key;

    private MasterKey(byte[] bytes) {

        if (bytes == null || bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                "A chave mestra deve conter exatamente 32 bytes."
            );
        }

        this.key = bytes.clone();
    }

    /**
     * Carrega ou cria a chave mestra.
     *
     * Nunca substitui automaticamente uma
     * chave existente.
     */
    public static synchronized MasterKey carregarOuCriar(
            Path caminho) throws IOException {

        if (caminho == null) {
            throw new IllegalArgumentException(
                "O caminho da chave mestra não pode ser nulo."
            );
        }

        if (!caminho.isAbsolute()) {
            throw new IllegalArgumentException(
                "O caminho da chave mestra deve ser absoluto: "
                + caminho
            );
        }

        Path ficheiro = caminho.normalize();

        Path pasta = ficheiro.getParent();

        if (pasta == null) {
            throw new IOException(
                "Não foi possível determinar a pasta da chave."
            );
        }

        /*
         * Criar as pastas individualmente,
         * verificando os componentes existentes
         * antes de avançar.
         */
        prepararDiretorio(pasta);

        /*
         * Não seguir uma ligação simbólica
         * no caminho final.
         */
        if (Files.exists(
                ficheiro,
                LinkOption.NOFOLLOW_LINKS)) {

            return carregarExistente(ficheiro);
        }

        return criarNova(ficheiro);
    }

    /**
     * Percorre o caminho absoluto componente
     * a componente.
     *
     * Cada diretório é verificado antes
     * de criar ou visitar o seguinte.
     */
    private static void prepararDiretorio(
            Path pasta) throws IOException {

        Path atual = pasta.getRoot();

        if (atual == null) {
            throw new IOException(
                "O diretório de dados deve ser absoluto."
            );
        }

        verificarDiretorio(atual);

        for (Path componente : pasta) {

            atual = atual.resolve(componente);

            if (!Files.exists(
                    atual,
                    LinkOption.NOFOLLOW_LINKS)) {

                try {

                    Files.createDirectory(atual);

                } catch (FileAlreadyExistsException e) {
                    // Outro processo criou o componente.
                    // Verificar o resultado abaixo.
                }
            }

            verificarDiretorio(atual);
        }
    }

    private static void verificarDiretorio(
            Path pasta) throws IOException {

        if (Files.isSymbolicLink(pasta)) {
            throw new IOException(
                "O caminho contém uma ligação simbólica: "
                + pasta
            );
        }

        if (!Files.isDirectory(
                pasta,
                LinkOption.NOFOLLOW_LINKS)) {

            throw new IOException(
                "Componente do caminho não é diretório: "
                + pasta
            );
        }
    }

    /**
     * Obtém o utilizador sob o qual a JVM
     * está a executar.
     *
     * Em Windows, a resolução depende
     * do fornecedor de identidades do sistema.
     * Se não for possível, o arranque falha.
     */
    private static UserPrincipal utilizadorAtual(
            Path ficheiro) throws IOException {

        String nome = System.getProperty("user.name");

        if (nome == null || nome.isBlank()) {
            throw new IOException(
                "Não foi possível identificar o utilizador atual."
            );
        }

        return ficheiro.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(nome);
    }

    private static AclEntry aclDoDono(
            UserPrincipal dono) {

        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(dono)
                .setPermissions(OWNER_ACL_PERMISSIONS)
                .build();
    }

    /**
     * Devolve atributos seguros para
     * a criação inicial do ficheiro.
     *
     * POSIX: permissões 0600.
     * Windows/ACL: lista de controlo de acesso
     * com uma única entrada para o dono.
     *
     * Se o fornecedor não suportar a criação
     * segura, a operação falha.
     */
    private static FileAttribute<?> atributoCriacao(
            Path ficheiro) throws IOException {

        Path pasta = ficheiro.getParent();

        PosixFileAttributeView posix =
                Files.getFileAttributeView(
                    pasta,
                    PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
                );

        if (posix != null) {
            return PosixFilePermissions.asFileAttribute(
                POSIX_PERMISSIONS
            );
        }

        AclFileAttributeView acl =
                Files.getFileAttributeView(
                    pasta,
                    AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
                );

        if (acl != null) {

            UserPrincipal utilizador =
                    utilizadorAtual(ficheiro);

            List<AclEntry> entradas = List.of(
                aclDoDono(utilizador)
            );

            return new FileAttribute<List<AclEntry>>() {

                @Override
                public String name() {
                    return "acl:acl";
                }

                @Override
                public List<AclEntry> value() {
                    return entradas;
                }
            };
        }

        throw new IOException(
            "O sistema de ficheiros não suporta "
            + "permissões POSIX nem ACL."
        );
    }

    /**
     * Verifica as permissões atuais.
     *
     * Não altera silenciosamente uma chave
     * existente com permissões inadequadas.
     */
    private static void verificarPermissoes(
            Path ficheiro) throws IOException {

        PosixFileAttributeView posix =
                Files.getFileAttributeView(
                    ficheiro,
                    PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
                );

        if (posix != null) {

            if (!Files.isRegularFile(
                    ficheiro,
                    LinkOption.NOFOLLOW_LINKS)) {

                throw new IOException(
                    "A chave não é um ficheiro regular."
                );
            }

            Set<PosixFilePermission> atuais =
                    posix.readAttributes().permissions();

            if (!atuais.equals(POSIX_PERMISSIONS)) {

                throw new IOException(
                    "Permissões inseguras na chave mestra: "
                    + "é necessário acesso exclusivo do dono (0600)."
                );
            }

            return;
        }

        AclFileAttributeView acl =
                Files.getFileAttributeView(
                    ficheiro,
                    AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
                );

        if (acl == null) {
            throw new IOException(
                "Não é possível verificar as permissões "
                + "da chave mestra."
            );
        }

        UserPrincipal dono = acl.getOwner();
        UserPrincipal utilizador = utilizadorAtual(ficheiro);

        if (!dono.equals(utilizador)) {
            throw new IOException(
                "O dono da chave mestra não corresponde "
                + "ao utilizador que executa o líder."
            );
        }

        List<AclEntry> entradas = acl.getAcl();

        /*
         * Política deliberadamente restritiva:
         * uma única entrada ALLOW para o dono,
         * sem DENY, entradas herdadas visíveis,
         * permissões extra ou outros intervenientes.
         */
        if (entradas.size() != 1) {
            throw new IOException(
                "ACL da chave mestra não é exclusiva: "
                + "existem entradas adicionais."
            );
        }

        AclEntry entrada = entradas.get(0);

        if (entrada.type() != AclEntryType.ALLOW
                || !entrada.principal().equals(dono)
                || !entrada.flags().isEmpty()
                || !entrada.permissions().equals(
                    OWNER_ACL_PERMISSIONS)) {

            throw new IOException(
                "ACL da chave mestra não cumpre "
                + "a política de acesso exclusivo do dono."
            );
        }

        /*
         * Esta validação verifica a ACL exposta
         * pelo fornecedor Java. Não substitui
         * uma auditoria de acesso efetivo do Windows.
         */
    }

    /**
     * Carrega uma chave existente.
     *
     * Lê apenas 32 bytes e testa se há
     * um byte adicional.
     */
    private static MasterKey carregarExistente(
            Path ficheiro) throws IOException {

        if (!Files.isRegularFile(
                ficheiro,
                LinkOption.NOFOLLOW_LINKS)) {

            throw new IOException(
                "A chave mestra existente não é "
                + "um ficheiro regular: " + ficheiro
            );
        }

        /*
         * Verificar antes de ler.
         * Isto não corrige exposições passadas.
         */
        verificarPermissoes(ficheiro);

        byte[] bytes = new byte[KEY_BYTES];

        try (FileChannel canal = FileChannel.open(
                ficheiro,
                Set.of(
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS
                ))) {

            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {

                int lidos = canal.read(buffer);

                if (lidos < 0) {
                    throw new IOException(
                        "Chave mestra inválida: "
                        + "menos de 32 bytes."
                    );
                }
            }

            ByteBuffer extra = ByteBuffer.allocate(1);

            if (canal.read(extra) != -1) {
                throw new IOException(
                    "Chave mestra inválida: "
                    + "mais de 32 bytes."
                );
            }

            return new MasterKey(bytes);

        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Cria e grava uma chave nova.
     *
     * Atributos de proteção são fornecidos
     * no momento da criação.
     */
    private static MasterKey criarNova(
            Path ficheiro) throws IOException {

        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);

        boolean criado = false;
        boolean concluido = false;

        Throwable erroPrincipal = null;

        try {

            FileAttribute<?> atributos =
                    atributoCriacao(ficheiro);

            try (FileChannel canal = FileChannel.open(
                    ficheiro,
                    Set.of(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS
                    ),
                    atributos)) {

                criado = true;

                // Validar antes de gravar os bytes.
                verificarPermissoes(ficheiro);

                ByteBuffer buffer = ByteBuffer.wrap(bytes);

                while (buffer.hasRemaining()) {
                    canal.write(buffer);
                }

                canal.force(true);

                if (canal.size() != KEY_BYTES) {
                    throw new IOException(
                        "A chave mestra não foi guardada "
                        + "com exatamente 32 bytes."
                    );
                }
            }

            // Verificação final antes de disponibilizar.
            verificarPermissoes(ficheiro);

            concluido = true;

            return new MasterKey(bytes);

        } catch (FileAlreadyExistsException e) {

            IOException erro = new IOException(
                "A chave mestra surgiu durante o arranque. "
                + "O ficheiro não será substituído.",
                e
            );

            erroPrincipal = erro;
            throw erro;

        } catch (IOException | RuntimeException | Error e) {

            erroPrincipal = e;
            throw e;

        } finally {

            if (criado && !concluido) {

                try {
                    Files.deleteIfExists(ficheiro);

                } catch (IOException erroLimpeza) {

                    if (erroPrincipal != null) {
                        erroPrincipal.addSuppressed(
                            erroLimpeza
                        );

                    } else {
                        throw erroLimpeza;
                    }
                }
            }

            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Devolve uma cópia defensiva dos bytes.
     *
     * O chamador é responsável por limpar
     * a cópia depois da sua utilização.
     */
    public byte[] getBytes() {
        return key.clone();
    }
}
