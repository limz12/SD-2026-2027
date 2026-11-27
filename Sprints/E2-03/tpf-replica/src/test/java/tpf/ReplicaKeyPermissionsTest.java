package tpf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReplicaKeyPermissionsTest {
    @TempDir Path data;

    @Test
    void ownerRestrictedKeyCanBeReadByTheCreatingProcess() throws Exception {
        Path key = data.resolve("replica.pk8");
        byte[] bytes = new byte[48];
        java.util.Arrays.fill(bytes, (byte) 19);

        ReplicaService.writePrivate(key, bytes);

        assertArrayEquals(bytes, Files.readAllBytes(key));
    }
}
