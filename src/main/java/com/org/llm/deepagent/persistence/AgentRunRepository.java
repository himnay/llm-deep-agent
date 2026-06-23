package com.org.llm.deepagent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentRun;
import com.org.llm.deepagent.agent.AgentRunStatus;
import com.org.llm.deepagent.agent.AgentStep;
import com.org.llm.deepagent.agent.PlannedAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Plain {@link JdbcTemplate} persistence for {@code agent_run}/{@code agent_step} — the audit trail
 * behind {@code GET /agent/run/{runId}}, and the durable state that makes a run resumable across
 * separate requests/threads (approval gates, sub-agent delegation, context compaction).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AgentRunRepository {

  private static final String RUN_COLUMNS =
      "id, session_id, prompt, status, final_answer, created_at, completed_at, "
          + "parent_run_id, root_run_id, context_summary, summarized_step_count, pending_action";
  private static final String SELECT_RUN_BY_ID =
      "SELECT " + RUN_COLUMNS + " FROM agent_run WHERE id = ?";
  private static final String SELECT_RECENT_RUNS_BY_SESSION =
      "SELECT "
          + RUN_COLUMNS
          + " FROM agent_run WHERE session_id = ? AND id != ? AND parent_run_id IS NULL "
          + "AND status IN ('COMPLETED', 'INCOMPLETE') ORDER BY created_at DESC LIMIT ?";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Creates a new top-level run (no parent); its {@code root_run_id} is set to its own id. */
  public long createRun(String sessionId, String prompt) {
    return createRun(sessionId, prompt, null, null);
  }

  /**
   * Creates a run, optionally as a sub-agent of {@code parentRunId}. When {@code rootRunId} is
   * {@code null} (a top-level run), the new row's {@code root_run_id} is set to its own generated
   * id; otherwise it inherits the given root so the whole delegation tree shares one task
   * list/scratchpad scope.
   */
  public long createRun(String sessionId, String prompt, Long parentRunId, Long rootRunId) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var ps =
              connection.prepareStatement(
                  "INSERT INTO agent_run (session_id, prompt, status, parent_run_id, root_run_id) "
                      + "VALUES (?, ?, ?, ?, ?)",
                  new String[] {"id"});
          ps.setString(1, sessionId);
          ps.setString(2, prompt);
          ps.setString(3, AgentRunStatus.RUNNING.name());
          ps.setObject(4, parentRunId);
          ps.setObject(5, rootRunId);
          return ps;
        },
        keyHolder);
    long runId = keyHolder.getKey().longValue();
    if (rootRunId == null) {
      jdbc.update("UPDATE agent_run SET root_run_id = ? WHERE id = ?", runId, runId);
    }
    return runId;
  }

  /** Persists one plan/act/observe step, in order, under its run. */
  public void saveStep(AgentStep step) {
    jdbc.update(
        """
        INSERT INTO agent_step (run_id, step_index, action, tool_name, input, observation, reasoning)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        step.runId(),
        step.stepIndex(),
        step.action().name(),
        step.toolName(),
        step.input(),
        step.observation(),
        step.reasoning());
  }

  /** Marks a run as finished (terminal: COMPLETED, INCOMPLETE or FAILED) with its final answer. */
  public void complete(long runId, AgentRunStatus status, String finalAnswer) {
    jdbc.update(
        "UPDATE agent_run SET status = ?, final_answer = ?, completed_at = NOW() WHERE id = ?",
        status.name(),
        finalAnswer,
        runId);
  }

  /**
   * Pauses a run on a gated action: status becomes AWAITING_APPROVAL, pending action is persisted.
   */
  public void markAwaitingApproval(long runId, PlannedAction pendingAction) {
    jdbc.update(
        "UPDATE agent_run SET status = ?, pending_action = ? WHERE id = ?",
        AgentRunStatus.AWAITING_APPROVAL.name(),
        writePendingAction(pendingAction),
        runId);
  }

  /** Clears a resolved pending action and puts the run back into RUNNING so the loop can resume. */
  public void clearPendingAction(long runId) {
    jdbc.update(
        "UPDATE agent_run SET status = ?, pending_action = NULL WHERE id = ?",
        AgentRunStatus.RUNNING.name(),
        runId);
  }

  /** Persists the rolling summary {@link com.org.llm.deepagent.agent.ContextCompactor} produces. */
  public void updateContextSummary(long runId, String summary, int summarizedStepCount) {
    jdbc.update(
        "UPDATE agent_run SET context_summary = ?, summarized_step_count = ? WHERE id = ?",
        summary,
        summarizedStepCount,
        runId);
  }

  /** Returns {@code null} when no run with this id exists (not an exception). */
  public AgentRun findById(long runId) {
    List<AgentRun> matches = jdbc.query(SELECT_RUN_BY_ID, this::mapRun, runId);
    AgentRun run = matches.isEmpty() ? null : matches.get(0);
    if (run == null) {
      return null;
    }
    List<AgentStep> steps = findStepsByRunId(runId);
    return new AgentRun(
        run.id(),
        run.sessionId(),
        run.prompt(),
        run.status(),
        run.finalAnswer(),
        steps,
        run.createdAt(),
        run.completedAt(),
        run.parentRunId(),
        run.rootRunId(),
        run.contextSummary(),
        run.summarizedStepCount(),
        run.pendingAction());
  }

  /**
   * All persisted steps for a run, ordered by {@code stepIndex} — used to rebuild the transcript.
   */
  public List<AgentStep> findStepsByRunId(long runId) {
    return jdbc.query(
        "SELECT id, run_id, step_index, action, tool_name, input, observation, reasoning, created_at "
            + "FROM agent_step WHERE run_id = ? ORDER BY step_index ASC",
        (rs, rowNum) ->
            new AgentStep(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getInt("step_index"),
                AgentAction.valueOf(rs.getString("action")),
                rs.getString("tool_name"),
                rs.getString("input"),
                rs.getString("observation"),
                rs.getString("reasoning"),
                toInstant(rs.getTimestamp("created_at"))),
        runId);
  }

  /**
   * The last {@code limit} resolved (COMPLETED/INCOMPLETE) top-level runs for {@code sessionId},
   * most recent first, excluding {@code excludeRunId} — used to seed a new run's planner prompt
   * with prior conversation context. Steps are not loaded (only prompt/finalAnswer are needed).
   */
  public List<AgentRun> findRecentBySession(String sessionId, long excludeRunId, int limit) {
    return jdbc.query(SELECT_RECENT_RUNS_BY_SESSION, this::mapRun, sessionId, excludeRunId, limit);
  }

  private AgentRun mapRun(ResultSet rs, int rowNum) throws SQLException {
    return new AgentRun(
        rs.getLong("id"),
        rs.getString("session_id"),
        rs.getString("prompt"),
        AgentRunStatus.valueOf(rs.getString("status")),
        rs.getString("final_answer"),
        List.of(),
        toInstant(rs.getTimestamp("created_at")),
        toInstant(rs.getTimestamp("completed_at")),
        (Long) rs.getObject("parent_run_id"),
        (Long) rs.getObject("root_run_id"),
        rs.getString("context_summary"),
        rs.getInt("summarized_step_count"),
        parsePendingAction(rs.getString("pending_action")));
  }

  private String writePendingAction(PlannedAction pendingAction) {
    try {
      return objectMapper.writeValueAsString(pendingAction);
    } catch (Exception e) {
      log.warn("AGENT_RUN_REPOSITORY | failed to serialize pending action | {}", e.getMessage());
      return null;
    }
  }

  private PlannedAction parsePendingAction(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, PlannedAction.class);
    } catch (Exception e) {
      log.warn(
          "AGENT_RUN_REPOSITORY | failed to parse persisted pending action | {}", e.getMessage());
      return null;
    }
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp != null ? timestamp.toInstant() : null;
  }
}
