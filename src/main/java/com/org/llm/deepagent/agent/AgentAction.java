package com.org.llm.orchestrator.agent;

/**
 * The set of next-step actions the planner LLM can choose between on each iteration of the
 * plan/act/observe loop. Each non-terminal value is handled by exactly one {@link
 * com.org.llm.orchestrator.routing.RoutingStrategy}.
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
  /** Terminal action — the loop ends and {@code input} is returned as the final answer. */
  FINAL_ANSWER
}
