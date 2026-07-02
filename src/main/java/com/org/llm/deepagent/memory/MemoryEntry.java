package com.org.llm.deepagent.memory;

import java.time.Instant;

/** One distilled fact from a past run, with its content embedding for similarity recall. */
public record MemoryEntry(
        long id,
        Long sourceRunId,
        String sessionId,
        String createdBy,
        String content,
        float[] embedding,
        Instant createdAt) {}
