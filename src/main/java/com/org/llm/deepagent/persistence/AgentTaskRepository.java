package com.org.llm.deepagent.persistence;

import com.org.llm.deepagent.agent.dto.AgentTask;
import com.org.llm.deepagent.agent.dto.AgentTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Plain {@link JdbcTemplate} persistence for {@code agent_task} — the planner-maintained todo list
 * behind the {@code PLAN_TASKS} action, scoped per run tree via {@code root_run_id}.
 */
@Repository
@RequiredArgsConstructor
public class AgentTaskRepository {

    private final JdbcTemplate jdbc;

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    /**
     * Replaces the entire task list for a run tree with {@code tasks} (snapshot semantics — the
     * planner resends its full desired state on every {@code PLAN_TASKS} call).
     */
    @Transactional
    public void replaceAll(long rootRunId, List<AgentTask> tasks) {
        jdbc.update("DELETE FROM agent_task WHERE root_run_id = ?", rootRunId);
        for (AgentTask task : tasks) {
            jdbc.update(
                    "INSERT INTO agent_task (root_run_id, task_key, description, status) VALUES (?, ?, ?, ?)",
                    rootRunId,
                    task.taskKey(),
                    task.description(),
                    task.status().name());
        }
    }

    /**
     * The current task list for a run tree, in insertion order (most recently created first).
     */
    public List<AgentTask> findByRootRunId(long rootRunId) {
        return jdbc.query(
                "SELECT id, root_run_id, task_key, description, status, updated_at "
                        + "FROM agent_task WHERE root_run_id = ? ORDER BY id ASC",
                (rs, rowNum) ->
                        new AgentTask(
                                rs.getLong("id"),
                                rs.getLong("root_run_id"),
                                rs.getString("task_key"),
                                rs.getString("description"),
                                AgentTaskStatus.valueOf(rs.getString("status")),
                                toInstant(rs.getTimestamp("updated_at"))),
                rootRunId);
    }
}
