-- Deep-agent features: sub-agent delegation, human-approval gating, context compaction,
-- a shared task list and a virtual filesystem (scratchpad) per run tree.

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS parent_run_id          BIGINT REFERENCES agent_run (id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS root_run_id            BIGINT REFERENCES agent_run (id),
    ADD COLUMN IF NOT EXISTS context_summary        TEXT,
    ADD COLUMN IF NOT EXISTS summarized_step_count  INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_action         TEXT;

CREATE INDEX IF NOT EXISTS idx_agent_run_parent_run_id ON agent_run (parent_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_root_run_id ON agent_run (root_run_id);

COMMENT ON COLUMN agent_run.parent_run_id IS 'Set when this run is a sub-agent delegated by DELEGATE_SUBAGENT.';
COMMENT ON COLUMN agent_run.root_run_id IS 'Top-level run id shared by a run and all of its sub-agents; tasks/scratchpad files are scoped to this id.';
COMMENT ON COLUMN agent_run.context_summary IS 'Rolling summary of steps aged out of the verbatim transcript by ContextCompactor.';
COMMENT ON COLUMN agent_run.summarized_step_count IS 'How many leading agent_step rows are already folded into context_summary.';
COMMENT ON COLUMN agent_run.pending_action IS 'JSON-serialized PlannedAction awaiting human approval when status = AWAITING_APPROVAL.';

CREATE TABLE IF NOT EXISTS agent_task (
    id          BIGSERIAL     PRIMARY KEY,
    root_run_id BIGINT        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    task_key    VARCHAR(50)   NOT NULL,
    description TEXT          NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (root_run_id, task_key)
);

CREATE TABLE IF NOT EXISTS agent_artifact (
    id          BIGSERIAL     PRIMARY KEY,
    root_run_id BIGINT        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    path        VARCHAR(255)  NOT NULL,
    content     TEXT          NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (root_run_id, path)
);

CREATE INDEX IF NOT EXISTS idx_agent_task_root_run_id ON agent_task (root_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_artifact_root_run_id ON agent_artifact (root_run_id);

COMMENT ON TABLE agent_task IS 'Planner-maintained todo list (PLAN_TASKS action), scoped to a run tree via root_run_id.';
COMMENT ON TABLE agent_artifact IS 'Virtual filesystem / scratchpad (FILE_WRITE, FILE_READ actions), scoped to a run tree via root_run_id.';
