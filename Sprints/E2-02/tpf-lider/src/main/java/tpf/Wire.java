package tpf;

import java.nio.file.Path;

public final class Wire {
    public static final int CHUNK_MAX_BYTES = positiveProperty("tpf.chunkMaxBytes", 65536);
    public static final int PAD_BYTES = positiveProperty("tpf.padBytes", 4096);
    public static final int SELAGEM_MS = positiveProperty("tpf.selagemMs", 5000);
    public static final int KEY_VERSION = 1;
    public static final String INFO = "tpf-chunk-v1";
    public static final byte[] AAD_PREFIX = "tpf-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static final Path DATA_DIR = defaultDataDir();

    private Wire() {}

    private static int positiveProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return defaultValue;
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalArgumentException(name + " deve ser positivo");
        return parsed;
    }

    private static Path defaultDataDir() {
        String configured = System.getProperty("tpf.dataDir");
        if (configured != null && !configured.isBlank()) return Path.of(configured);

        Path cwd = Path.of("").toAbsolutePath();
        if (cwd.getFileName() != null && cwd.getFileName().toString().equalsIgnoreCase("tpf-lider")) {
            return cwd;
        }
        return Path.of("Sprints", "E2-02", "tpf-lider");
    }
}
