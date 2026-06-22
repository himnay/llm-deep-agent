package com.org.llm.orchestrator.agent;

import java.time.Instant;
import java.util.List;

/** A single agent invocation — the persisted unit returned by {@code GET /agent/run/{runId}}. */
public record AgentRun(
    Long id,
    String sessionId,
    String prompt,
    AgentRunStatus status,
    String finalAnswer,
    List<AgentStep> steps,
    Instant createdAt,
    Instant completedAt) {}
