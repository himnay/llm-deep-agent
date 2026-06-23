package com.org.llm.deepagent.agent;

import java.time.Instant;

/**
 * One file of a run tree's scratchpad, persisted as a row in {@code agent_artifact} and addressed
 * by {@code path} within {@code rootRunId}. Written/read via {@code FILE_WRITE}/{@code FILE_READ}.
 */
public record AgentArtifact(
    Long id, Long rootRunId, String path, String content, Instant updatedAt) {}
