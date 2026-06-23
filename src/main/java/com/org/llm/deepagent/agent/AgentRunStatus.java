package com.org.llm.deepagent.agent;

public enum AgentRunStatus {
  RUNNING,
  /**
   * Paused on a gated action (see {@code agent.approval-required-actions}); resume via
   * approve/reject.
   */
  AWAITING_APPROVAL,
  COMPLETED,
  /** Hit {@code agent.max-iterations} without the planner returning FINAL_ANSWER. */
  INCOMPLETE,
  FAILED
}
