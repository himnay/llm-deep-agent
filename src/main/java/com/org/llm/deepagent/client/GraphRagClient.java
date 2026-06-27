package com.org.llm.deepagent.client;

import com.org.llm.deepagent.client.dto.GraphRagRequest;
import com.org.llm.deepagent.client.dto.GraphRagResponse;
import com.org.llm.deepagent.security.UrlAllowlistValidator;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Thin client over llm-rag-graph's HTTP API ({@code POST /api/v1/rag/query}).
 */
@Slf4j
@Component
public class GraphRagClient {

    private final GraphRagProperties properties;
    private final PlatformTokenService tokenService;
    private final RestClient restClient;

    public GraphRagClient(
            RestClient.Builder restClientBuilder,
            GraphRagProperties properties,
            PlatformTokenService tokenService,
            UrlAllowlistValidator urlValidator) {
        urlValidator.validate(properties.getBaseUrl(), "graph-rag.base-url");
        this.properties = properties;
        this.tokenService = tokenService;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    @Retry(name = "graph-rag", fallbackMethod = "queryFallback")
    @CircuitBreaker(name = "graph-rag", fallbackMethod = "queryCircuitFallback")
    public GraphRagResponse query(String question) {
        return restClient
                .post()
                .uri("/rag/query")
                .header("Authorization", "Bearer " + tokenService.getToken())
                .body(new GraphRagRequest(question))
                .retrieve()
                .body(GraphRagResponse.class);
    }

    GraphRagResponse queryFallback(String question, Throwable ex) {
        log.warn("GRAPH_RAG_CLIENT | query retries exhausted | {}", ex.getMessage());
        return emptyResponse("retries exhausted");
    }

    GraphRagResponse queryCircuitFallback(String question, Throwable ex) {
        log.warn("GRAPH_RAG_CLIENT | query circuit open | {}", ex.getMessage());
        return emptyResponse("circuit open");
    }

    private GraphRagResponse emptyResponse(String reason) {
        return new GraphRagResponse(
                "llm-rag-graph unavailable (" + reason + ")",
                null,
                List.of(),
                null);
    }
}
