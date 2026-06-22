package com.org.llm.orchestrator.client.dto;

/** Mirrors llm-rag-pipeline's {@code GenerateRequest}. */
public record RagGenerateRequest(String query, Integer topK, String conversationId) {}
