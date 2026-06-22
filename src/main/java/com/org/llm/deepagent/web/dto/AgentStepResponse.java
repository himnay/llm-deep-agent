package com.org.llm.orchestrator.web.dto;

import com.org.llm.orchestrator.agent.AgentAction;
import com.org.llm.orchestrator.agent.AgentStep;

public record AgentStepResponse(
    int stepIndex,
    AgentAction action,
    String toolName,
    String input,
    String observation,
    String reasoning) {

  public static AgentStepResponse from(AgentStep step) {
    return new AgentStepResponse(
        step.stepIndex(),
        step.action(),
        step.toolName(),
        step.input(),
        step.observation(),
        step.reasoning());
  }
}
