-- Audit trail for every agent run: one row per invocation, one row per plan/act/observe step.
CREATE TABLE IF NOT EXISTS agent_run (
    id           BIGSERIAL     PRIMARY KEY,
    session_id   VARCHAR(100),
    prompt       TEXT          NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    final_answer TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS agent_step (
    id          BIGSERIAL     PRIMARY KEY,
    run_id      BIGINT        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    step_index  INT           NOT NULL,
    action      VARCHAR(20)   NOT NULL,
    tool_name   VARCHAR(100),
    input       TEXT,
    observation TEXT,
    reasoning   TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_step_run_id ON agent_step (run_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_session_id ON agent_run (session_id);

COMMENT ON TABLE agent_run IS 'One row per agent invocation (POST /agent/run).';
COMMENT ON TABLE agent_step IS 'One row per plan/act/observe loop iteration within a run.';
