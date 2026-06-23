package com.org.llm.deepagent.routing;

/**
 * Carries per-run identity through a single {@link RoutingStrategy} dispatch.
 *
 * @param runId the run this step belongs to (a sub-agent's own id when dispatched inside a nested
 *     run)
 * @param rootRunId the top-level run id shared by a run and all of its sub-agents — task list and
 *     scratchpad files are scoped to this id so a sub-agent and its parent see the same state
 * @param sessionId the caller-supplied conversation/session identifier, or {@code null}
 * @param isSubAgent {@code true} when {@code runId} itself was created by {@code DELEGATE_SUBAGENT}
 */
public record AgentContext(Long runId, Long rootRunId, String sessionId, boolean isSubAgent) {}
