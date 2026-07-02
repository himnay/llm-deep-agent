-- Long-term agent memory: facts distilled from completed runs, recalled on new runs
-- via embedding cosine similarity (embeddings computed through llm-gateway /embed).

CREATE TABLE IF NOT EXISTS agent_memory (
    id            BIGSERIAL    PRIMARY KEY,
    source_run_id BIGINT       REFERENCES agent_run (id) ON DELETE SET NULL,
    session_id    VARCHAR(255),
    created_by    VARCHAR(255),
    content       TEXT         NOT NULL,
    embedding     TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_memory_created_at ON agent_memory (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_memory_created_by ON agent_memory (created_by);

COMMENT ON TABLE agent_memory IS 'Facts distilled from completed agent runs; recalled into the planner prompt of later runs by embedding similarity.';
COMMENT ON COLUMN agent_memory.embedding IS 'JSON float[] of the content embedding (computed via llm-gateway /embed); NULL when embedding failed — such rows are never recalled.';
