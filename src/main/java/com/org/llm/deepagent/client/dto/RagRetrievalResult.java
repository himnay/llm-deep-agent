package com.org.llm.orchestrator.client.dto;

import java.util.List;

/** Mirrors llm-rag-pipeline's {@code RetrievalResult} (response of {@code POST /retrieve}). */
public record RagRetrievalResult(List<RagChunk> chunks, List<RagCitation> citations) {}
