package com.org.llm.deepagent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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
                    + "parent_run_id, root_run_id, context_summary, summarized_step_count, pending_action, "
                    + "created_by, total_tokens_used";
    private static final String SELECT_RUN_BY_ID =
            "SELECT " + RUN_COLUMNS + " FROM agent_run WHERE id = ?";
    private static final String SELECT_RECENT_RUNS_BY_SESSION =
            "SELECT "
                    + RUN_COLUMNS
                    + " FROM agent_run WHERE session_id = ? AND id != ? AND parent_run_id IS NULL "
                    + "AND status IN ('COMPLETED', 'INCOMPLETE') ORDER BY created_at DESC LIMIT ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    /**
     * Creates a new top-level, unowned run (no parent, no creator); its {@code root_run_id}
     * self-references.
     */
    public long createRun(String sessionId, String prompt) {
        return createRun(sessionId, prompt, null, null, null);
    }

    /**
     * Creates a run with an explicit parent/root but no recorded creator — see the 5-arg overload.
     */
    public long createRun(String sessionId, String prompt, Long parentRunId, Long rootRunId) {
        return createRun(sessionId, prompt, parentRunId, rootRunId, null);
    }

    /**
     * Creates a run, optionally as a sub-agent of {@code parentRunId}. When {@code rootRunId} is
     * {@code null} (a top-level run), the new row's {@code root_run_id} is set to its own generated
     * id; otherwise it inherits the given root so the whole delegation tree shares one task
     * list/scratchpad scope. {@code createdBy} drives the ownership check in {@code AgentController}.
     */
    public long createRun(
            String sessionId, String prompt, Long parentRunId, Long rootRunId, String createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var ps =
                            connection.prepareStatement(
                                    "INSERT INTO agent_run (session_id, prompt, status, parent_run_id, root_run_id, created_by) "
                                            + "VALUES (?, ?, ?, ?, ?, ?)",
                                    new String[]{"id"});
                    ps.setString(1, sessionId);
                    ps.setString(2, prompt);
                    ps.setString(3, AgentRunStatus.RUNNING.name());
                    ps.setObject(4, parentRunId);
                    ps.setObject(5, rootRunId);
                    ps.setString(6, createdBy);
                    return ps;
                },
                keyHolder);
        long runId = keyHolder.getKey().longValue();
        if (rootRunId == null) {
            jdbc.update("UPDATE agent_run SET root_run_id = ? WHERE id = ?", runId, runId);
        }
        return runId;
    }

    /**
     * Persists one plan/act/observe step, in order, under its run.
     */
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

    /**
     * Marks a run as finished (terminal: COMPLETED, INCOMPLETE or FAILED) with its final answer.
     */
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

    /**
     * Atomically claims the right to resolve a run's pending action: flips status from {@code
     * AWAITING_APPROVAL} to {@code RUNNING} and clears {@code pending_action}, but only if the run is
     * still {@code AWAITING_APPROVAL} at the moment the {@code UPDATE} executes. The database's
     * row-level locking serializes concurrent callers, so when two requests race to approve/reject
     * the same run, exactly one {@code UPDATE} matches the {@code WHERE} clause — returns {@code
     * true} for that caller and {@code false} for every other, preventing the same pending action
     * (e.g. an MCP tool call) from being dispatched twice.
     */
    public boolean claimPendingAction(long runId) {
        int updated =
                jdbc.update(
                        "UPDATE agent_run SET status = ?, pending_action = NULL WHERE id = ? AND status = ?",
                        AgentRunStatus.RUNNING.name(),
                        runId,
                        AgentRunStatus.AWAITING_APPROVAL.name());
        return updated > 0;
    }

    /**
     * Persists the rolling summary {@link com.org.llm.deepagent.agent.ContextCompactor} produces.
     */
    public void updateContextSummary(long runId, String summary, int summarizedStepCount) {
        jdbc.update(
                "UPDATE agent_run SET context_summary = ?, summarized_step_count = ? WHERE id = ?",
                summary,
                summarizedStepCount,
                runId);
    }

    /**
     * Atomically adds to a run's cumulative token spend (a no-op when {@code tokens} is
     * non-positive).
     */
    public void            addTokensUsed(long runId, int tokens) {
        if (tokens <= 0) {
            return;
        }
        jdbc.update(
                "UPDATE agent_run SET total_tokens_used = total_tokens_used + ? WHERE id = ?",
                tokens,
                runId);
    }

    /**
     * Atomically cancels a run if it's still in a non-terminal state ({@code RUNNING} or {@code
     * AWAITING_APPROVAL}); returns {@code false} (no-op) if it had already reached a terminal status.
     */
    public boolean cancelIfNotTerminal(long runId) {
        int updated =
                jdbc.update(
                        "UPDATE agent_run SET status = ?, completed_at = NOW() WHERE id = ? AND status IN (?, ?)",
                        AgentRunStatus.CANCELLED.name(),
                        runId,
                        AgentRunStatus.RUNNING.name(),
                        AgentRunStatus.AWAITING_APPROVAL.name());
        return updated > 0;
    }

    /**
     * Cheap per-iteration read of just status + token spend — see {@link RunControlState}.
     */
    public RunControlState findControlState(long runId) {
        List<RunControlState> matches =
                jdbc.query(
                        "SELECT status, total_tokens_used FROM agent_run WHERE id = ?",
                        (rs, rowNum) ->
                                new RunControlState(
                                        AgentRunStatus.valueOf(rs.getString("status")), rs.getInt("total_tokens_used")),
                        runId);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Deletes terminal top-level runs (and, via {@code ON DELETE CASCADE}, their steps/tasks/
     * artifacts/sub-agents) older than {@code cutoff} — see {@code AgentRunRetentionJob}. Returns the
     * number of top-level rows deleted.
     */
    public int deleteTerminalRunsCompletedBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM agent_run WHERE parent_run_id IS NULL AND completed_at IS NOT NULL AND completed_at < ? "
                        + "AND status IN (?, ?, ?, ?)",
                Timestamp.from(cutoff),
                AgentRunStatus.COMPLETED.name(),
                AgentRunStatus.INCOMPLETE.name(),
                AgentRunStatus.FAILED.name(),
                AgentRunStatus.CANCELLED.name());
    }

    /**
     * All runs currently RUNNING — orphaned survivors of a crash if found right after this instance
     * boots.
     */
    public List<Long> findAllRunningRunIds() {
        return jdbc.queryForList(
                "SELECT id FROM agent_run WHERE status = ?", Long.class, AgentRunStatus.RUNNING.name());
    }

    /**
     * Returns {@code null} when no run with this id exists (not an exception).
     */
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
                run.pendingAction(),
                run.createdBy(),
                run.totalTokensUsed());
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
                parsePendingAction(rs.getString("pending_action")),
                rs.getString("created_by"),
                rs.getInt("total_tokens_used"));
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
}
