package com.org.llm.deepagent.agent;

/** Lifecycle of one entry in the planner-maintained task list ({@code PLAN_TASKS}). */
public enum AgentTaskStatus {
  PENDING,
  IN_PROGRESS,
  COMPLETED
}
