package com.org.llm.deepagent.agent.dto;

import java.time.Instant;

/**
 * One entry of a run tree's todo list, persisted as a row in {@code agent_task} and replaced
 * wholesale on every {@code PLAN_TASKS} step (snapshot semantics, not incremental edits).
 */
public record AgentTask(
        Long id,
        Long rootRunId,
        String taskKey,
        String description,
        AgentTaskStatus status,
        Instant updatedAt) {
}
