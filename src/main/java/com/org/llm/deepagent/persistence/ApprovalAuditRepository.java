package com.org.llm.deepagent.persistence;

import com.org.llm.deepagent.agent.ApprovalAuditEntry;
import com.org.llm.deepagent.agent.ApprovalDecision;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain {@link JdbcTemplate} persistence for {@code agent_approval_audit} — who approved/rejected
 * which gated action, and why. Kept separate from {@code agent_step} so the accountability record
 * survives even if the step itself is later pruned by retention cleanup.
 */
@Repository
@RequiredArgsConstructor
public class ApprovalAuditRepository {

  private final JdbcTemplate jdbc;

  /** Records one human decision on a gated action. */
  public void record(
      long runId, int stepIndex, ApprovalDecision decision, String actor, String reason) {
    jdbc.update(
        "INSERT INTO agent_approval_audit (run_id, step_index, decision, actor, reason) VALUES (?, ?, ?, ?, ?)",
        runId,
        stepIndex,
        decision.name(),
        actor,
        reason);
  }

  /** Every recorded decision for a run, oldest first. */
  public List<ApprovalAuditEntry> findByRunId(long runId) {
    return jdbc.query(
        "SELECT id, run_id, step_index, decision, actor, reason, decided_at "
            + "FROM agent_approval_audit WHERE run_id = ? ORDER BY id ASC",
        (rs, rowNum) ->
            new ApprovalAuditEntry(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getInt("step_index"),
                ApprovalDecision.valueOf(rs.getString("decision")),
                rs.getString("actor"),
                rs.getString("reason"),
                toInstant(rs.getTimestamp("decided_at"))),
        runId);
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp != null ? timestamp.toInstant() : null;
  }
}
