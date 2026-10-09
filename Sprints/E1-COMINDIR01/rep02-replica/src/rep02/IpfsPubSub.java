import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

public class IpfsPubSub {
    private final String apiBaseUrl;
    private final HttpClient client;

    // Construtor que recebe a String do URL base da API (ex: http://127.0.0.1:5001/api/v0)
    public IpfsPubSub(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.client = HttpClient.newHttpClient();
    }

    public IpfsPubSub() {
        this("http://127.0.0.1:5001/api/v0");
    }

    public static String toMultibase(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return "u" + b64;
    }

    public static String fromMultibase(String multibaseStr) {
        if (multibaseStr == null || multibaseStr.isEmpty()) {
            return "";
        }
        if (multibaseStr.startsWith("u")) {
            multibaseStr = multibaseStr.substring(1);
        }
        byte[] decodedBytes = Base64.getUrlDecoder().decode(multibaseStr);
        return new String(decodedBytes, StandardCharsets.UTF_8);
    }

    public void publish(String topic, String message) {
        try {
            String mbTopic = toMultibase(topic);
            URI uri = URI.create(apiBaseUrl + "/pubsub/pub?arg=" + URLEncoder.encode(mbTopic, StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .POST(HttpRequest.BodyPublishers.ofString(message))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("Erro ao publicar no IPFS PubSub: " + response.body());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Método subscribe solicitado pela ficha (compatível com Wire.topicIn(k))
    public Stream<String> subscribe(String topic) {
        try {
            String mbTopic = toMultibase(topic);
            URI uri = URI.create(apiBaseUrl + "/pubsub/sub?arg=" + URLEncoder.encode(mbTopic, StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8)).lines();
        } catch (Exception e) {
            e.printStackTrace();
            return Stream.empty();
        }
    }

    // Extrai o campo "data" de uma mensagem JSON do PubSub e decodifica-o
    public static String topicData(String jsonLine) {
        try {
            int dataIdx = jsonLine.indexOf("\"data\":\"");
            if (dataIdx != -1) {
                int start = dataIdx + 8;
                int end = jsonLine.indexOf("\"", start);
                if (end != -1) {
                    String mbData = jsonLine.substring(start, end);
                    return fromMultibase(mbData);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
}