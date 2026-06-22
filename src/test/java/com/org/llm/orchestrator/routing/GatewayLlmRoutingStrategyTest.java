package com.org.llm.orchestrator.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.org.llm.orchestrator.agent.AgentAction;
import com.org.llm.orchestrator.agent.PlannedAction;
import com.org.llm.orchestrator.client.GatewayClient;
import com.org.llm.orchestrator.client.dto.GatewayChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GatewayLlmRoutingStrategyTest {

  private final GatewayClient gatewayClient = mock(GatewayClient.class);
  private final GatewayLlmRoutingStrategy strategy = new GatewayLlmRoutingStrategy(gatewayClient);

  @Test
  @DisplayName("supports() only matches GATEWAY_LLM")
  void supportsOnlyGatewayLlm() {
    assertThat(strategy.supports(AgentAction.GATEWAY_LLM)).isTrue();
    assertThat(strategy.supports(AgentAction.RAG_RETRIEVE)).isFalse();
    assertThat(strategy.supports(AgentAction.MCP_TOOL)).isFalse();
  }

  @Test
  @DisplayName("execute() returns the gateway's content as the observation on success")
  void executeReturnsContentOnSuccess() {
    when(gatewayClient.chat("hello", null, "session-1"))
        .thenReturn(
            new GatewayChatResponse("hi there", "gpt-4o", "openai", null, 1, 1, 2, 10L, "req-1"));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, "session-1"),
            new PlannedAction(AgentAction.GATEWAY_LLM, null, "hello", "just chat"));

    assertThat(result.success()).isTrue();
    assertThat(result.observation()).isEqualTo("hi there");
  }

  @Test
  @DisplayName("execute() surfaces the gateway's error as a failed observation")
  void executeSurfacesError() {
    when(gatewayClient.chat("hello", null, null))
        .thenReturn(
            new GatewayChatResponse(
                null, null, "llm-gateway-core", "boom", null, null, null, 0L, null));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, null),
            new PlannedAction(AgentAction.GATEWAY_LLM, null, "hello", null));

    assertThat(result.success()).isFalse();
    assertThat(result.observation()).isEqualTo("boom");
  }
}
