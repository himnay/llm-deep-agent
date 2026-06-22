package com.org.llm.orchestrator.persistence;

import com.org.llm.orchestrator.agent.AgentAction;
import com.org.llm.orchestrator.agent.AgentRun;
import com.org.llm.orchestrator.agent.AgentRunStatus;
import com.org.llm.orchestrator.agent.AgentStep;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Plain {@link JdbcTemplate} persistence for {@code agent_run}/{@code agent_step} — the audit trail
 * behind {@code GET /agent/run/{runId}}.
 */
@Repository
@RequiredArgsConstructor
public class AgentRunRepository {

  private final JdbcTemplate jdbc;

  public long createRun(String sessionId, String prompt) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var ps =
              connection.prepareStatement(
                  "INSERT INTO agent_run (session_id, prompt, status) VALUES (?, ?, ?)",
                  new String[] {"id"});
          ps.setString(1, sessionId);
          ps.setString(2, prompt);
          ps.setString(3, AgentRunStatus.RUNNING.name());
          return ps;
        },
        keyHolder);
    return keyHolder.getKey().longValue();
  }

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

  public void complete(long runId, AgentRunStatus status, String finalAnswer) {
    jdbc.update(
        "UPDATE agent_run SET status = ?, final_answer = ?, completed_at = NOW() WHERE id = ?",
        status.name(),
        finalAnswer,
        runId);
  }

  /** Returns {@code null} when no run with this id exists (not an exception). */
  public AgentRun findById(long runId) {
    List<AgentRun> matches =
        jdbc.query(
            "SELECT id, session_id, prompt, status, final_answer, created_at, completed_at FROM agent_run WHERE id = ?",
            (rs, rowNum) ->
                new AgentRun(
                    rs.getLong("id"),
                    rs.getString("session_id"),
                    rs.getString("prompt"),
                    AgentRunStatus.valueOf(rs.getString("status")),
                    rs.getString("final_answer"),
                    List.of(),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("completed_at"))),
            runId);

    AgentRun run = matches.isEmpty() ? null : matches.get(0);
    if (run == null) {
      return null;
    }

    List<AgentStep> steps =
        jdbc.query(
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

    return new AgentRun(
        run.id(),
        run.sessionId(),
        run.prompt(),
        run.status(),
        run.finalAnswer(),
        steps,
        run.createdAt(),
        run.completedAt());
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp != null ? timestamp.toInstant() : null;
  }
}
