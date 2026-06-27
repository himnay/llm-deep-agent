package com.org.llm.deepagent.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for calling llm-rag-pipeline (prefix {@code rag}).
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * llm-rag-pipeline base URL including its {@code /api/v1} base path.
     */
    private String baseUrl = "http://localhost:8081/api/v1";

    /**
     * Per-call timeout (seconds) for blocking RAG requests.
     */
    private int timeoutSeconds = 60;
}
