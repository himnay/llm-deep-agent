-- Hardening pass: ownership/authorization, approval audit trail, and a per-run token budget.

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS created_by        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS total_tokens_used  INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent_run.created_by IS 'JWT subject (or "anonymous" when auth is disabled) that started this run; inherited by its sub-agents. NULL for rows created before this column existed — those are treated as unrestricted.';
COMMENT ON COLUMN agent_run.total_tokens_used IS 'Cumulative prompt+completion tokens spent by this run (planner calls + compaction summarization), checked against agent.max-total-tokens.';

CREATE TABLE IF NOT EXISTS agent_approval_audit (
    id          BIGSERIAL     PRIMARY KEY,
    run_id      BIGINT        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    step_index  INT           NOT NULL,
    decision    VARCHAR(10)   NOT NULL,
    actor       VARCHAR(255),
    reason      TEXT,
    decided_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_approval_audit_run_id ON agent_approval_audit (run_id);

COMMENT ON TABLE agent_approval_audit IS 'Who approved/rejected which gated action, and why — independent of agent_step so the decision survives even if the step itself is later pruned by retention cleanup.';
