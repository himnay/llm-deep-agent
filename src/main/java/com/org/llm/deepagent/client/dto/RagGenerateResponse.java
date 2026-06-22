package com.org.llm.orchestrator.client.dto;

import java.util.List;

/** Mirrors llm-rag-pipeline's {@code GenerateResponse} (response of {@code POST /generate}). */
public record RagGenerateResponse(
    String answer,
    List<RagCitation> citations,
    Boolean faithful,
    boolean fromSemanticCache,
    boolean insufficientContext) {}
