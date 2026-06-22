package com.org.llm.orchestrator.routing;

/** The observation fed back to the planner LLM after a {@link RoutingStrategy} runs. */
public record StepResult(String observation, boolean success) {

  public static StepResult ok(String observation) {
    return new StepResult(observation, true);
  }

  public static StepResult error(String observation) {
    return new StepResult(observation, false);
  }
}
