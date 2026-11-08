package raft04;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class IpfsPubSub {
    private final String api;
    private final HttpClient http = HttpClient.newHttpClient();

    public IpfsPubSub(String api) { this.api = api; }

    private static String multibase(String text) {
        return "u" + Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String unmultibase(String text) {
        if (!text.startsWith("u")) throw new IllegalArgumentException("Codificação multibase não suportada");
        return new String(Base64.getUrlDecoder().decode(text.substring(1)), StandardCharsets.UTF_8);
    }

    public void publish(String topic, String message) throws IOException, InterruptedException {
        String boundary = "raft04-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n" + message + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/pubsub/pub?arg=" + multibase(topic)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("pub falhou (" + response.statusCode() + "): " + response.body());
    }

    public void subscribe(String topic, Consumer<String> onMessage) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/pubsub/sub?arg=" + multibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) throw new IOException("sub falhou (" + response.statusCode() + ")");
        response.body().filter(line -> line.contains("\"data\""))
                .forEach(line -> onMessage.accept(unmultibase(extractField(line, "data"))));
    }

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) throw new IllegalArgumentException("Campo '" + field + "' ausente");
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) throw new IllegalArgumentException("Campo '" + field + "' inválido");
        return json.substring(start, end);
    }
}
