package com.org.llm.deepagent.client.dto;

/**
 * Mirrors llm-rag-pipeline's {@code RetrieveRequest}.
 */
public record RagRetrieveRequest(String query, Integer topK) {
}
