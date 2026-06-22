package com.org.llm.orchestrator.client.dto;

/** Mirrors llm-rag-pipeline's {@code Citation}. */
public record RagCitation(
    String source, String fileName, String identity, Integer page, int chunkIndex, Double score) {}
