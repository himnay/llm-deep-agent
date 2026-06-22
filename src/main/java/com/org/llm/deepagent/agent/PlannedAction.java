package com.org.llm.orchestrator.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The planner LLM's structured decision for the next loop iteration, parsed from its JSON response
 * (see {@code prompts/planner-system-prompt} rendered by {@code AgentLoopExecutor}).
 *
 * @param action which {@link AgentAction} to take next
 * @param toolName the MCP tool name, only set when {@code action == MCP_TOOL}
 * @param input the query/prompt/tool-arguments-as-JSON for this step, or the final answer text when
 *     {@code action == FINAL_ANSWER}
 * @param reasoning the planner's own one-line rationale, kept for the audit trail/debugging
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlannedAction(AgentAction action, String toolName, String input, String reasoning) {}
