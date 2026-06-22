package com.org.llm.orchestrator.agent;

public enum AgentRunStatus {
  RUNNING,
  COMPLETED,
  /** Hit {@code agent.max-iterations} without the planner returning FINAL_ANSWER. */
  INCOMPLETE,
  FAILED
}
