package com.org.llm.orchestrator.client.dto;

/** Mirrors llm-rag-pipeline's {@code RetrieveRequest}. */
public record RagRetrieveRequest(String query, Integer topK) {}
