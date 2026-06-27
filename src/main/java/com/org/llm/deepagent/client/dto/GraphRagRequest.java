package com.org.llm.deepagent.client.dto;

/**
 * Request body for POST /api/v1/rag/query on llm-rag-graph.
 */
public record GraphRagRequest(String question) {
}
