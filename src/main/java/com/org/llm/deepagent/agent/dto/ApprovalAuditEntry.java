package com.org.llm.deepagent.agent.dto;

import java.time.Instant;

/**
 * One human decision on a gated action, persisted as a row in {@code agent_approval_audit}.
 */
public record ApprovalAuditEntry(
        Long id,
        Long runId,
        int stepIndex,
        ApprovalDecision decision,
        String actor,
        String reason,
        Instant decidedAt) {
}
