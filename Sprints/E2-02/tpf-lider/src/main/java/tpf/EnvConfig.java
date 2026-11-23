package tpf;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvConfig {
    private static final Dotenv dotenv = loadDotenv();

    public static String get(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = dotenv.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável em falta no .env: " + key);
        }
        return value;
    }

    private static Dotenv loadDotenv() {
        String[] directories = {
                "Sprints/E2-02/tpf-lider/src/main/java/tpf",
                "src/main/java/tpf",
                "."
        };
        for (String directory : directories) {
            Dotenv candidate = Dotenv.configure()
                    .directory(directory)
                    .ignoreIfMissing()
                    .load();
            if (candidate.get("BOT_TOKEN") != null || candidate.get("GUILD_ID") != null) {
                return candidate;
            }
        }
        return Dotenv.configure().directory(".").ignoreIfMissing().load();
    }
}