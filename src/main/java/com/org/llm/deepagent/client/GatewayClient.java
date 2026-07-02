package com.org.llm.deepagent.client;

import com.org.llm.deepagent.client.dto.GatewayChatRequest;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import com.org.llm.deepagent.security.UrlAllowlistValidator;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Thin client over llm-gateway-core's HTTP API ({@code POST /chat}). This is what the agent loop
 * calls for both the "plan" step (ask the LLM what to do next) and any step the {@code
 * GatewayLlmRoutingStrategy} dispatches as a plain LLM call.
 */
@Slf4j
@Component
public class GatewayClient {

    private final GatewayProperties properties;
    private final PlatformTokenService tokenService;
    private final RestClient restClient;

    public GatewayClient(
            RestClient.Builder restClientBuilder,
            GatewayProperties properties,
            PlatformTokenService tokenService,
            UrlAllowlistValidator urlValidator) {
        urlValidator.validate(properties.getBaseUrl(), "gateway.base-url");
        this.properties = properties;
        this.tokenService = tokenService;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    @Retry(name = "gateway", fallbackMethod = "retryFallback")
    @CircuitBreaker(name = "gateway", fallbackMethod = "circuitBreakerFallback")
    public GatewayChatResponse chat(String prompt, String systemPrompt, String sessionId) {
        GatewayChatRequest request =
                new GatewayChatRequest(
                        prompt, properties.getProvider().toUpperCase(), null, systemPrompt, sessionId);

        return restClient
                .post()
                .uri("/chat")
                .header("Authorization", "Bearer " + tokenService.getToken())
                .body(request)
                .retrieve()
                .body(GatewayChatResponse.class);
    }

    /**
     * One-shot, stateless completion via {@code POST /query} (used for planning decisions).
     */
    @Retry(name = "gateway", fallbackMethod = "retryFallback")
    @CircuitBreaker(name = "gateway", fallbackMethod = "circuitBreakerFallback")
    public GatewayChatResponse query(String prompt, String systemPrompt) {
        GatewayChatRequest request =
                new GatewayChatRequest(
                        prompt, properties.getProvider().toUpperCase(), null, systemPrompt, null);

        return restClient
                .post()
                .uri("/query")
                .header("Authorization", "Bearer " + tokenService.getToken())
                .body(request)
                .retrieve()
                .body(GatewayChatResponse.class);
    }

    /**
     * Computes a text embedding via {@code POST /embed}. Returns {@code null} on any failure —
     * callers (long-term memory) treat a missing embedding as "skip", never as an error.
     */
    @Retry(name = "gateway", fallbackMethod = "embedFallback")
    @CircuitBreaker(name = "gateway", fallbackMethod = "embedFallback")
    public float[] embed(String text) {
        GatewayEmbedResponse response =
                restClient
                        .post()
                        .uri("/embed")
                        .header("Authorization", "Bearer " + tokenService.getToken())
                        .body(java.util.Map.of("text", text))
                        .retrieve()
                        .body(GatewayEmbedResponse.class);
        if (response == null || response.embedding() == null) {
            return null;
        }
        float[] vec = new float[response.embedding().size()];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = response.embedding().get(i).floatValue();
        }
        return vec;
    }

    float[] embedFallback(String text, Throwable ex) {
        log.warn("GATEWAY_CLIENT | embed unavailable | {}", ex.getMessage());
        return null;
    }

    /** Minimal projection of llm-gateway-core's embedding response. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GatewayEmbedResponse(java.util.List<Double> embedding) {}

    GatewayChatResponse circuitBreakerFallback(
            String prompt, String systemPrompt, String sessionId, Throwable ex) {
        return fallback("circuit open", ex);
    }

    GatewayChatResponse retryFallback(
            String prompt, String systemPrompt, String sessionId, Throwable ex) {
        return fallback("retries exhausted", ex);
    }

    GatewayChatResponse circuitBreakerFallback(String prompt, String systemPrompt, Throwable ex) {
        return fallback("circuit open", ex);
    }

    GatewayChatResponse retryFallback(String prompt, String systemPrompt, Throwable ex) {
        return fallback("retries exhausted", ex);
    }

    private GatewayChatResponse fallback(String reason, Throwable ex) {
        log.warn("GATEWAY_CLIENT | {} | {}", reason, ex.getMessage());
        return new GatewayChatResponse(
                null,
                null,
                "llm-gateway-core",
                "llm-gateway-core unavailable (" + reason + ")",
                null,
                null,
                null,
                Duration.ZERO.toMillis(),
                null);
    }
}
