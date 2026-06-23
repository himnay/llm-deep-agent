package com.org.llm.deepagent.routing;

import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.PlannedAction;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

/** Dispatches {@link AgentAction#MCP_TOOL} steps to the matching MCP tool, by name. */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRoutingStrategy implements RoutingStrategy {

  private final ToolCallbackProvider toolCallbackProvider;

  @Override
  public boolean supports(AgentAction action) {
    return action == AgentAction.MCP_TOOL;
  }

  @Override
  public StepResult execute(AgentContext context, PlannedAction plannedAction) {
    String toolName = plannedAction.toolName();
    if (toolName == null || toolName.isBlank()) {
      return StepResult.error("MCP_TOOL action requires a toolName");
    }

    ToolCallback callback =
        Arrays.stream(toolCallbackProvider.getToolCallbacks())
            .filter(tc -> tc.getToolDefinition().name().equals(toolName))
            .findFirst()
            .orElse(null);

    if (callback == null) {
      return StepResult.error("Unknown or currently unavailable MCP tool: " + toolName);
    }

    try {
      String toolArgsJson = plannedAction.input() != null ? plannedAction.input() : "{}";
      return StepResult.ok(callback.call(toolArgsJson));
    } catch (Exception e) {
      log.error("MCP_ROUTING | tool '{}' failed | {}", toolName, e.getMessage());
      return StepResult.error("MCP tool '" + toolName + "' failed: " + e.getMessage());
    }
  }
}
