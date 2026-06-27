package com.org.llm.deepagent.agent.dto;

import java.time.Instant;

/**
 * One plan/act/observe iteration, persisted as a row in {@code agent_step}.
 */
public record AgentStep(
        Long id,
        Long runId,
        int stepIndex,
        AgentAction action,
        String toolName,
        String input,
        String observation,
        String reasoning,
        Instant createdAt) {
}
