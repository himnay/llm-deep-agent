package com.org.llm.deepagent.agent;

import java.time.Instant;
import java.util.List;

/**
 * A single agent invocation — the persisted unit returned by {@code GET /agent/run/{runId}}.
 *
 * @param parentRunId set when this run is a sub-agent delegated by {@code DELEGATE_SUBAGENT}
 * @param rootRunId the top-level run id shared by this run and all of its sub-agents
 * @param contextSummary {@link ContextCompactor}'s rolling summary of steps aged out of the
 *     verbatim transcript, or {@code null} before compaction has triggered
 * @param summarizedStepCount how many leading steps are already folded into {@code contextSummary}
 * @param pendingAction the action awaiting human approval, set only when {@code status ==
 *     AWAITING_APPROVAL}
 */
public record AgentRun(
    Long id,
    String sessionId,
    String prompt,
    AgentRunStatus status,
    String finalAnswer,
    List<AgentStep> steps,
    Instant createdAt,
    Instant completedAt,
    Long parentRunId,
    Long rootRunId,
    String contextSummary,
    int summarizedStepCount,
    PlannedAction pendingAction) {}
