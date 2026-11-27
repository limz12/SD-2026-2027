package tpf;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReplicaStoreTest {
    @TempDir Path data;

    @Test
    void storesOnlyHashNamedBytesAndDuplicateStoreIsIdempotent() throws Exception {
        byte[] bin = "encrypted bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] header = "1;c2FsdA==;bm9uY2U=\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bin));
        ReplicaStore store = new ReplicaStore(data, 1024);

        assertEquals(ReplicaStore.Result.STORED, store.store(id, header, bin));
        assertEquals(ReplicaStore.Result.EXISTS, store.store(id, header, bin));
        assertEquals(bin.length, store.used());
        assertArrayEquals(bin, Files.readAllBytes(data.resolve(id + ".bin")));
        assertArrayEquals(header, Files.readAllBytes(data.resolve(id + ".hdr")));
    }

    @Test
    void rejectsHashMismatchAndDoesNotExceedQuota() throws Exception {
        byte[] bin = new byte[128];
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bin));
        ReplicaStore full = new ReplicaStore(data, 127);
        String incorrectId = "f".repeat(64);
        assertEquals(ReplicaStore.Result.QUOTA, full.store(incorrectId, new byte[]{1}, bin));
        assertEquals(0, full.used());
        assertFalse(Files.exists(data.resolve(id + ".bin")));
        ReplicaStore available = new ReplicaStore(data, 1024);
        assertThrows(java.io.IOException.class, () -> available.store(incorrectId, new byte[]{1}, bin));
    }

    @Test
    void completesValidBinLeftByInterruptedTwoFilePublication() throws Exception {
        byte[] bin = "encrypted bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] header = "header".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bin));
        Files.write(data.resolve(id + ".bin"), bin);
        ReplicaStore recovered = new ReplicaStore(data, 1024);

        assertEquals(ReplicaStore.Result.STORED, recovered.store(id, header, bin));
        assertEquals(bin.length, recovered.used());
        assertArrayEquals(header, Files.readAllBytes(data.resolve(id + ".hdr")));
    }
}
