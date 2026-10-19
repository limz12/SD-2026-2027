package rep02;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Cliente mínimo da API HTTP do daemon Kubo local, sem dependências externas. */
public class IpfsPubSub {
    private final String api;
    private final HttpClient http = HttpClient.newHttpClient();

    public IpfsPubSub(String api) {
        if (api == null || api.isBlank()) {
            throw new IllegalArgumentException("Endereço da API IPFS vazio");
        }
        this.api = api.replaceAll("/$", "");
    }

    private static String toMultibase(String value) {
        return "u" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String fromMultibase(String value) {
        if (!value.startsWith("u")) {
            throw new IllegalArgumentException("Codificação multibase não suportada: " + value);
        }
        return new String(Base64.getUrlDecoder().decode(value.substring(1)), StandardCharsets.UTF_8);
    }

    /** Publica uma mensagem no tópico indicado. */
    public void publish(String topic, String message) throws IOException, InterruptedException {
        String boundary = "rep02-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + message + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(api + "/pubsub/pub?arg=" + toMultibase(topic)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("pub falhou (" + response.statusCode() + "): " + response.body());
        }
    }

    /** Subscreve indefinidamente; cada mensagem recebida é entregue ao callback. */
    public void subscribe(String topic, Consumer<String> onMessage)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(api + "/pubsub/sub?arg=" + toMultibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("sub falhou (" + response.statusCode() + ")");
        }
        try (Stream<String> lines = response.body()) {
            lines.filter(json -> json.contains("\"data\""))
                    .forEach(json -> onMessage.accept(fromMultibase(extractField(json, "data"))));
        }
    }

    /** Extrai um campo textual do JSON simples produzido pelo endpoint PubSub do Kubo. */
    private static String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            throw new IllegalArgumentException("Campo '" + field + "' ausente: " + json);
        }
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new IllegalArgumentException("Campo '" + field + "' incompleto: " + json);
        }
        return json.substring(start, end);
    }
}
