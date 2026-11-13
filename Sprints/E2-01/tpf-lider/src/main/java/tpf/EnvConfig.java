package tpf;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvConfig {
    private static final Dotenv dotenv = Dotenv.configure()
            .directory("Sprints/E2-01/tpf-lider/src/main/java/tpf")
            .ignoreIfMissing()
            .load();

    public static String get(String key) {
        String value = dotenv.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável em falta no .env: " + key);
        }
        return value;
    }
}