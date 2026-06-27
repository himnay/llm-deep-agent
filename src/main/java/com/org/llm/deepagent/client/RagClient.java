package com.org.llm.deepagent.client;

import com.org.llm.deepagent.client.dto.RagGenerateRequest;
import com.org.llm.deepagent.client.dto.RagGenerateResponse;
import com.org.llm.deepagent.client.dto.RagRetrievalResult;
import com.org.llm.deepagent.client.dto.RagRetrieveRequest;
import com.org.llm.deepagent.security.UrlAllowlistValidator;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin client over llm-rag-pipeline's HTTP API ({@code POST /retrieve}, {@code POST /generate}) —
 * what the agent loop calls when the planner decides a step needs grounded, citation-backed context
 * instead of a raw LLM call.
 */
@Slf4j
@Component
public class RagClient {

    private final RagProperties properties;
    private final PlatformTokenService tokenService;
    private final RestClient restClient;

    public RagClient(
            RestClient.Builder restClientBuilder,
            RagProperties properties,
            PlatformTokenService tokenService,
            UrlAllowlistValidator urlValidator) {
        urlValidator.validate(properties.getBaseUrl(), "rag.base-url");
        this.properties = properties;
        this.tokenService = tokenService;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    @Retry(name = "rag", fallbackMethod = "retrieveFallback")
    @CircuitBreaker(name = "rag", fallbackMethod = "retrieveCircuitFallback")
    public RagRetrievalResult retrieve(String query, Integer topK) {
        return restClient
                .post()
                .uri("/retrieve")
                .header("Authorization", "Bearer " + tokenService.getToken())
                .body(new RagRetrieveRequest(query, topK))
                .retrieve()
                .body(RagRetrievalResult.class);
    }

    @Retry(name = "rag", fallbackMethod = "generateFallback")
    @CircuitBreaker(name = "rag", fallbackMethod = "generateCircuitFallback")
    public RagGenerateResponse generate(String query, Integer topK, String conversationId) {
        return restClient
                .post()
                .uri("/generate")
                .header("Authorization", "Bearer " + tokenService.getToken())
                .body(new RagGenerateRequest(query, topK, conversationId))
                .retrieve()
                .body(RagGenerateResponse.class);
    }

    RagRetrievalResult retrieveFallback(String query, Integer topK, Throwable ex) {
        log.warn("RAG_CLIENT | retrieve retries exhausted | {}", ex.getMessage());
        return new RagRetrievalResult(java.util.List.of(), java.util.List.of());
    }

    RagRetrievalResult retrieveCircuitFallback(String query, Integer topK, Throwable ex) {
        log.warn("RAG_CLIENT | retrieve circuit open | {}", ex.getMessage());
        return new RagRetrievalResult(java.util.List.of(), java.util.List.of());
    }

    RagGenerateResponse generateFallback(
            String query, Integer topK, String conversationId, Throwable ex) {
        return generateFallbackResponse("retries exhausted", ex);
    }

    RagGenerateResponse generateCircuitFallback(
            String query, Integer topK, String conversationId, Throwable ex) {
        return generateFallbackResponse("circuit open", ex);
    }

    private RagGenerateResponse generateFallbackResponse(String reason, Throwable ex) {
        log.warn("RAG_CLIENT | generate {} | {}", reason, ex.getMessage());
        return new RagGenerateResponse(
                "llm-rag-pipeline unavailable (" + reason + ")", java.util.List.of(), false, false, true);
    }
}
