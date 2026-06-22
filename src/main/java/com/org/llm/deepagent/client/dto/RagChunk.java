package com.org.llm.orchestrator.client.dto;

import java.util.Map;

/** Mirrors llm-rag-pipeline's {@code Chunk}. */
public record RagChunk(
    String source, String content, Map<String, Object> metadata, int chunkIndex) {}
