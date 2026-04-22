package knox.spring.data.neo4j.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin client for NVIDIA's hosted Evo 2 NIM (arc/evo2-40b /generate endpoint).
 *
 * Given a 5' DNA context "seed" and a number of tokens to emit, returns the
 * generated extension plus the mean sampled probability reported by the NIM.
 * Used by SequenceViewController to do single-slot "generative fill" against
 * an enumerated Knox design.
 *
 * Configured via application.properties:
 *   nvidia.api.key   -> bearer token (nvapi-...)
 *   nvidia.evo2.url  -> full /generate endpoint URL (override only if self-hosting)
 */
@Service
public class Evo2Service {

    private static final String DISABLED = "disabled";

    @Value("${nvidia.api.key:}")
    private String apiKey;

    @Value("${nvidia.evo2.url:https://health.api.nvidia.com/v1/biology/arc/evo2-40b/generate}")
    private String endpoint;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Evo2Result(String generated, double meanSampledProb,
                             long elapsedMs, String error) {}

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !DISABLED.equals(apiKey);
    }

    /**
     * Call Evo 2 /generate with the given 5' seed, asking it to emit numTokens
     * more tokens greedily (top_k=1) and return per-token sampled probabilities.
     * Returns an Evo2Result with error populated instead of throwing, so the
     * REST controller can surface it cleanly in the UI.
     */
    public Evo2Result fillForward(String seed, int numTokens) {
        if (!isConfigured()) {
            return new Evo2Result("", 0.0, 0, "NVIDIA_API_KEY not set");
        }
        if (seed == null) seed = "";
        if (numTokens <= 0) numTokens = 32;
        if (numTokens > 4096) numTokens = 4096;

        Map<String, Object> body = new HashMap<>();
        body.put("sequence", seed);
        body.put("num_tokens", numTokens);
        body.put("top_k", 1);
        body.put("enable_sampled_probs", true);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            // The NVIDIA NIM gateway occasionally returns transient 5xx — one
            // retry with a short backoff gets through in almost every case
            // (including the 502 Bad Gateway the user hit on a 12 bp slot).
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 500 && resp.statusCode() < 600) {
                System.out.println("Evo2 transient " + resp.statusCode() + " — retrying in 1.5s");
                try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            }
            if (resp.statusCode() != 200) {
                String b = resp.body() == null ? "" : resp.body();
                return new Evo2Result("", 0.0, 0,
                    "Evo2 HTTP " + resp.statusCode() + " (after retry): " +
                        b.substring(0, Math.min(b.length(), 300)));
            }
            JsonNode node = mapper.readTree(resp.body());
            String generated = node.path("sequence").asText("");
            long elapsed = node.path("elapsed_ms").asLong(0);
            double mean = 0.0;
            JsonNode probs = node.path("sampled_probs");
            if (probs.isArray() && probs.size() > 0) {
                double sum = 0.0;
                for (JsonNode p : probs) sum += p.asDouble();
                mean = sum / probs.size();
            }
            return new Evo2Result(generated, mean, elapsed, "");
        } catch (Exception e) {
            return new Evo2Result("", 0.0, 0, "Evo2 call failed: " + e.getMessage());
        }
    }
}
