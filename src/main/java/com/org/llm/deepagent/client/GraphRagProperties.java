package com.org.llm.deepagent.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for calling llm-rag-graph (prefix {@code graph-rag}).
 */
@Data
@ConfigurationProperties(prefix = "graph-rag")
public class GraphRagProperties {

    /**
     * llm-rag-graph base URL including its /api/v1 base path.
     */
    private String baseUrl = "http://localhost:8083/api/v1";

    /**
     * Per-call timeout (seconds) for blocking graph RAG requests.
     */
    private int timeoutSeconds = 60;

    /**
     * Whether the graph RAG service is available. When false, HybridRagRoutingStrategy
     * falls back to vector RAG only.
     */
    private boolean enabled = true;
}
