package com.org.llm.deepagent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Plain {@link JdbcTemplate} persistence for {@code agent_memory}. Embeddings are stored as JSON
 * float[] text — recall loads a bounded candidate window (newest first) and ranks in memory, which
 * keeps the schema portable; swap for pgvector if the memory count outgrows the window scan.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MemoryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public void save(Long sourceRunId, String sessionId, String createdBy, String content, float[] embedding) {
        String embeddingJson = null;
        if (embedding != null) {
            try {
                embeddingJson = objectMapper.writeValueAsString(embedding);
            } catch (Exception e) {
                log.warn("MEMORY | could not serialize embedding, storing fact without it | {}", e.getMessage());
            }
        }
        jdbc.update(
                "INSERT INTO agent_memory (source_run_id, session_id, created_by, content, embedding) VALUES (?, ?, ?, ?, ?)",
                sourceRunId,
                sessionId,
                createdBy,
                content,
                embeddingJson);
    }

    /** Newest memories that have an embedding, bounded to {@code limit} recall candidates. */
    public List<MemoryEntry> findRecentWithEmbedding(int limit) {
        return jdbc.query(
                "SELECT id, source_run_id, session_id, created_by, content, embedding, created_at "
                        + "FROM agent_memory WHERE embedding IS NOT NULL ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> {
                    float[] embedding;
                    try {
                        embedding = objectMapper.readValue(rs.getString("embedding"), float[].class);
                    } catch (Exception e) {
                        embedding = null;
                    }
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    long sourceRunId = rs.getLong("source_run_id");
                    return new MemoryEntry(
                            rs.getLong("id"),
                            rs.wasNull() ? null : sourceRunId,
                            rs.getString("session_id"),
                            rs.getString("created_by"),
                            rs.getString("content"),
                            embedding,
                            createdAt != null ? createdAt.toInstant() : null);
                },
                limit);
    }

    /** Retention hook: drops memories older than the cutoff, returning the number deleted. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM agent_memory WHERE created_at < ?", Timestamp.from(cutoff));
    }
}
