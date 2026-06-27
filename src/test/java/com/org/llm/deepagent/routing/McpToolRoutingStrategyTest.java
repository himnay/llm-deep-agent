package com.org.llm.deepagent.routing;

import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.dto.PlannedAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolRoutingStrategyTest {

    private final ToolCallbackProvider toolCallbackProvider = mock(ToolCallbackProvider.class);
    private final McpToolRoutingStrategy strategy = new McpToolRoutingStrategy(toolCallbackProvider);

    @Test
    @DisplayName("supports() only matches MCP_TOOL")
    void supportsOnlyMcpTool() {
        assertThat(strategy.supports(AgentAction.MCP_TOOL)).isTrue();
        assertThat(strategy.supports(AgentAction.GATEWAY_LLM)).isFalse();
    }

    @Test
    @DisplayName("execute() rejects a planned action with no toolName")
    void executeRejectsMissingToolName() {
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 1L, null, false),
                        new PlannedAction(AgentAction.MCP_TOOL, null, "{}", null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("requires a toolName");
    }

    @Test
    @DisplayName("execute() rejects an unknown tool name")
    void executeRejectsUnknownTool() {
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 1L, null, false),
                        new PlannedAction(AgentAction.MCP_TOOL, "getDeployments", "{}", null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("Unknown or currently unavailable");
    }

    @Test
    @DisplayName("execute() invokes the matching tool and returns its result")
    void executeInvokesMatchingTool() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("getDeployments");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call("{\"id\":1}")).thenReturn("[{\"id\":1,\"status\":\"SCHEDULED\"}]");
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{callback});

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 1L, null, false),
                        new PlannedAction(AgentAction.MCP_TOOL, "getDeployments", "{\"id\":1}", null));

        assertThat(result.success()).isTrue();
        assertThat(result.observation()).contains("SCHEDULED");
    }

    @Test
    @DisplayName("execute() surfaces tool exceptions as a failed observation rather than throwing")
    void executeSurfacesToolExceptions() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("getDeployments");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call("{}")).thenThrow(new RuntimeException("downstream boom"));
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{callback});

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 1L, null, false),
                        new PlannedAction(AgentAction.MCP_TOOL, "getDeployments", "{}", null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("downstream boom");
    }
}
