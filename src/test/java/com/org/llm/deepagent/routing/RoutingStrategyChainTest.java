package com.org.llm.deepagent.routing;

import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.dto.PlannedAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingStrategyChainTest {

    @Test
    @DisplayName("dispatch() delegates to the first strategy whose supports() matches")
    void dispatchDelegatesToMatchingStrategy() {
        RoutingStrategy gatewayStrategy = mock(RoutingStrategy.class);
        when(gatewayStrategy.supports(AgentAction.GATEWAY_LLM)).thenReturn(true);
        when(gatewayStrategy.execute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(StepResult.ok("handled"));

        RoutingStrategy ragStrategy = mock(RoutingStrategy.class);
        when(ragStrategy.supports(AgentAction.GATEWAY_LLM)).thenReturn(false);

        RoutingStrategyChain chain = new RoutingStrategyChain(List.of(ragStrategy, gatewayStrategy));

        StepResult result =
                chain.dispatch(
                        new AgentContext(1L, 1L, null, false),
                        new PlannedAction(AgentAction.GATEWAY_LLM, null, "hi", null));

        assertThat(result.success()).isTrue();
        assertThat(result.observation()).isEqualTo("handled");
    }

    @Test
    @DisplayName("dispatch() returns an error StepResult when no strategy supports the action")
    void dispatchReturnsErrorWhenUnsupported() {
        RoutingStrategyChain chain = new RoutingStrategyChain(List.of());

        StepResult result =
                chain.dispatch(
                        new AgentContext(1L, 1L, null, false),
                        new PlannedAction(AgentAction.MCP_TOOL, "x", "{}", null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("No routing strategy registered");
    }
}
