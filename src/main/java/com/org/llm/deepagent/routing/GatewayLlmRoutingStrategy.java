package com.org.llm.deepagent.routing;

import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.PlannedAction;
import com.org.llm.deepagent.client.GatewayClient;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Dispatches {@link AgentAction#GATEWAY_LLM} steps to llm-gateway-core. */
@Component
@RequiredArgsConstructor
public class GatewayLlmRoutingStrategy implements RoutingStrategy {

  private final GatewayClient gatewayClient;

  @Override
  public boolean supports(AgentAction action) {
    return action == AgentAction.GATEWAY_LLM;
  }

  @Override
  public StepResult execute(AgentContext context, PlannedAction plannedAction) {
    GatewayChatResponse response =
        gatewayClient.chat(plannedAction.input(), null, context.sessionId());
    if (response.error() != null) {
      return StepResult.error(response.error());
    }
    return StepResult.ok(response.content());
  }
}
