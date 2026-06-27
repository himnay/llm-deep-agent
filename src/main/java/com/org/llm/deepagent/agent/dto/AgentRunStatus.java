package com.org.llm.deepagent.agent.dto;

public enum AgentRunStatus {
    RUNNING,
    /**
     * Paused on a gated action (see {@code agent.approval-required-actions}); resume via
     * approve/reject.
     */
    AWAITING_APPROVAL,
    COMPLETED,
    /**
     * Hit {@code agent.max-iterations} or {@code agent.max-total-tokens} without a FINAL_ANSWER.
     */
    INCOMPLETE,
    FAILED,
    /**
     * Stopped via {@code POST /agent/run/{id}/cancel} before reaching a final answer.
     */
    CANCELLED
}
