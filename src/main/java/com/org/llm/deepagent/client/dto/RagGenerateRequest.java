package com.org.llm.deepagent.client.dto;

/**
 * Mirrors llm-rag-pipeline's {@code GenerateRequest}.
 */
public record RagGenerateRequest(String query, Integer topK, String conversationId) {
}
