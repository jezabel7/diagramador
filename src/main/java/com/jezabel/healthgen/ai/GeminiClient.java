package com.jezabel.healthgen.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Service
public class GeminiClient {

    private final GeminiProperties props;
    private final ObjectMapper om;
    private final HttpClient http;

    public GeminiClient(GeminiProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    public String generateText(String prompt) {
        String apiKey = props.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Falta gemini.api.key (o env var GEMINI_API_KEY/GOOGLE_API_KEY).");
        }

        // endpoint típico: https://generativelanguage.googleapis.com/v1beta/models
        String url = props.getEndpoint() + "/" + props.getModel() + ":generateContent?key=" + apiKey;

        try {
            String json = om.writeValueAsString(Map.of(
                    "contents", new Object[] {
                            Map.of("parts", new Object[] { Map.of("text", prompt) })
                    },
                    "generationConfig", Map.of("temperature", props.getTemperature())
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new RuntimeException("Gemini HTTP " + res.statusCode() + ": " + res.body());
            }

            JsonNode root = om.readTree(res.body());
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            String text = textNode.asText("");
            return text.isBlank() ? res.body() : text;

        } catch (Exception e) {
            throw new RuntimeException("Error llamando a Gemini: " + e.getMessage(), e);
        }
    }
}
