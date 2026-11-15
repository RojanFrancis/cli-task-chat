package io.github.rojanfrancis.clichat.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal Gemini REST client. The key goes in a header, never in the URL, so it cannot
 * end up in access logs. Every failure returns empty and the caller falls back to rules.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GeminiClient(@Value("${gemini.api-key:}") String apiKey,
                        @Value("${gemini.model:gemini-3.6-flash}") String model,
                        ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.mapper = mapper;
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    public String model() {
        return model;
    }

    /** Sends one prompt; {@code json} asks Gemini to answer with a JSON object only. */
    public Optional<String> generate(String systemInstruction, String prompt, boolean json, int maxTokens) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
            body.putArray("contents").addObject().put("role", "user")
                    .putArray("parts").addObject().put("text", prompt);
            ObjectNode config = body.putObject("generationConfig");
            config.put("temperature", 0.2).put("maxOutputTokens", maxTokens);
            if (json) {
                config.put("responseMimeType", "application/json");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Gemini returned HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode parts = mapper.readTree(response.body()).path("candidates").path(0).path("content").path("parts");
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.get("text").asText());
                }
            }
            return text.isEmpty() ? Optional.empty() : Optional.of(text.toString().trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Gemini call failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
