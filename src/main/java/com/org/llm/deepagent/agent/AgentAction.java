package com.org.llm.deepagent.agent;

/**
 * The set of next-step actions the planner LLM can choose between on each iteration of the
 * plan/act/observe loop. Each non-terminal value is handled by exactly one {@link
 * com.org.llm.deepagent.routing.RoutingStrategy}.
 */
public enum AgentAction {
  /** A plain LLM call via llm-gateway-core — no external grounding needed. */
  GATEWAY_LLM,
  /** Fetch grounded chunks/citations from llm-rag-pipeline without generation. */
  RAG_RETRIEVE,
  /** Full RAG answer (retrieval + generation + faithfulness check) from llm-rag-pipeline. */
  RAG_GENERATE,
  /** Invoke a named MCP tool exposed by one of llm-mcp's servers. */
  MCP_TOOL,
  /** Replace the run's task list with the JSON array of tasks given in {@code input}. */
  PLAN_TASKS,
  /** Write {@code input} (a JSON {@code {"path":...,"content":...}} object) to the scratchpad. */
  FILE_WRITE,
  /** Read back a scratchpad file named by {@code input} (a JSON {@code {"path":...}} object). */
  FILE_READ,
  /** Delegate {@code input} as a sub-task to an isolated, nested agent run. */
  DELEGATE_SUBAGENT,
  /** Terminal action — the loop ends and {@code input} is returned as the final answer. */
  FINAL_ANSWER
}
