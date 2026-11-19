
package tpf;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;

public final class MasterKey {

    private static final int KEY_SIZE = 32;

    private MasterKey() {}

    public static byte[] loadOrCreate(Path path) throws Exception {

        if (Files.exists(path)) {

            byte[] key = Files.readAllBytes(path);

            if (key.length != KEY_SIZE) {
                throw new IllegalStateException(
                    "master.key inválida: deve ter 32 bytes."
                );
            }

            return key;
        }

        // Gerar chave aleatória de 32 bytes
        byte[] key = new byte[KEY_SIZE];
        new SecureRandom().nextBytes(key);

        // Criar ficheiro apenas se ainda não existir
        Files.createFile(path);

        // Configurar permissões no Windows
        AclFileAttributeView aclView =
            Files.getFileAttributeView(
                path,
                AclFileAttributeView.class
            );

        if (aclView == null) {
            throw new UnsupportedOperationException(
                "O sistema de ficheiros não suporta ACL."
            );
        }
    
       

        // Guardar a chave no ficheiro antes de definir as permissões
        Files.write(path, key);
          
        UserPrincipal owner = Files.getOwner(path);
        System.out.println("Proprietário do ficheiro: " + owner.getName());
          
         //so o o dono do ficheiro tem permissões de leitura e escrita
        AclEntry entry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.DELETE
            )
            .build();
             // Aplicar permissões
        aclView.setAcl(java.util.List.of(entry));

        return key;
    }
}
