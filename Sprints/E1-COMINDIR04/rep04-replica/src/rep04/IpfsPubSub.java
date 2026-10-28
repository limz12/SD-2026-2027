package rep04;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Encapsula a API HTTP (RPC) do nÃ³ Kubo LOCAL. O programa Java NÃƒO fala com as
 * rÃ©plicas, nem com outros nÃ³s: fala sÃ³ com o daemon da sua prÃ³pria mÃ¡quina, por
 * HTTP em http://127.0.0.1:5001/api/v0. Ã‰ o daemon que faz o gossip com os outros nÃ³s.
 */
public class IpfsPubSub {
    private final String api; // ex.: http://127.0.0.1:5001/api/v0
    private final HttpClient http = HttpClient.newHttpClient();

    public IpfsPubSub(String api) {
        this.api = api;
    }

    // o Kubo exige o tÃ³pico e os dados em multibase: base64url sem padding, com prefixo 'u'
    // (sem isto o tÃ³pico que o Kubo vÃª Ã© outro, e lÃ­der e rÃ©plicas nunca se encontram)
    static String toMultibase(String s) {
        return "u" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String fromMultibase(String s) {
        if (!s.startsWith("u")) {
            throw new IllegalArgumentException("CodificaÃ§Ã£o multibase nÃ£o suportada: " + s);
        }
        return new String(Base64.getUrlDecoder().decode(s.substring(1)), StandardCharsets.UTF_8);
    }

    // publica uma mensagem no tÃ³pico (a mensagem segue no corpo, como ficheiro multipart)
    public void publish(String topic, String message) throws IOException, InterruptedException {
        String boundary = "rep01-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + message + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/pubsub/pub?arg=" + toMultibase(topic)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        // Daemon parado: http.send lanÃ§a java.net.ConnectException (uma IOException) AQUI, no lÃ­der.
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("pub falhou (" + resp.statusCode() + "): " + resp.body());
        }
    }

    // subscreve o tÃ³pico; NÃƒO TERMINA: cada linha da resposta Ã© uma mensagem JSON recebida
    public void subscribe(String topic, Consumer<String> onMessage) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/pubsub/sub?arg=" + toMultibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() != 200) {
            throw new IOException("sub falhou (" + resp.statusCode() + ")");
        }
        resp.body() // BLOQUEIA Ã  espera de cada linha (a resposta nunca termina)
                .filter(json -> json.contains("\"data\""))
                .forEach(json -> onMessage.accept(fromMultibase(extractField(json, "data"))));
    }

    // extraÃ§Ã£o mÃ­nima de um campo de texto de um objeto JSON simples (evita bibliotecas externas)
    static String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            throw new IllegalArgumentException("Campo '" + field + "' ausente: " + json);
        }
        start += key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
