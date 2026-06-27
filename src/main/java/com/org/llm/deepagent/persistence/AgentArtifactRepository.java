package com.org.llm.deepagent.persistence;

import com.org.llm.deepagent.agent.dto.AgentArtifact;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Plain {@link JdbcTemplate} persistence for {@code agent_artifact} — the virtual filesystem behind
 * the {@code FILE_WRITE}/{@code FILE_READ} actions, scoped per run tree via {@code root_run_id}.
 */
@Repository
@RequiredArgsConstructor
public class AgentArtifactRepository {

    private final JdbcTemplate jdbc;

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    /**
     * Creates or overwrites the file at {@code path} within a run tree's scratchpad.
     * Atomic ON CONFLICT prevents duplicate-key race when parent and sub-agent write concurrently.
     */
    public void upsert(long rootRunId, String path, String content) {
        jdbc.update(
                "INSERT INTO agent_artifact (root_run_id, path, content, updated_at) "
                        + "VALUES (?, ?, ?, NOW()) "
                        + "ON CONFLICT (root_run_id, path) "
                        + "DO UPDATE SET content = EXCLUDED.content, updated_at = NOW()",
                rootRunId,
                path,
                content);
    }

    /**
     * The current content of one scratchpad file, or empty if it doesn't exist.
     */
    public Optional<AgentArtifact> findByRootRunIdAndPath(long rootRunId, String path) {
        List<AgentArtifact> matches =
                jdbc.query(
                        "SELECT id, root_run_id, path, content, updated_at FROM agent_artifact "
                                + "WHERE root_run_id = ? AND path = ?",
                        (rs, rowNum) ->
                                new AgentArtifact(
                                        rs.getLong("id"),
                                        rs.getLong("root_run_id"),
                                        rs.getString("path"),
                                        rs.getString("content"),
                                        toInstant(rs.getTimestamp("updated_at"))),
                        rootRunId,
                        path);
        return matches.stream().findFirst();
    }

    /**
     * Every file currently in a run tree's scratchpad, without content (path + last-updated only).
     */
    public List<AgentArtifact> listByRootRunId(long rootRunId) {
        return jdbc.query(
                "SELECT id, root_run_id, path, '' AS content, updated_at FROM agent_artifact "
                        + "WHERE root_run_id = ? ORDER BY path ASC",
                (rs, rowNum) ->
                        new AgentArtifact(
                                rs.getLong("id"),
                                rs.getLong("root_run_id"),
                                rs.getString("path"),
                                rs.getString("content"),
                                toInstant(rs.getTimestamp("updated_at"))),
                rootRunId);
    }

    /**
     * Number of distinct files currently stored for a run tree — used to enforce the scratchpad cap.
     */
    public int countByRootRunId(long rootRunId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_artifact WHERE root_run_id = ?", Integer.class, rootRunId);
        return count == null ? 0 : count;
    }
}
