package com.org.llm.orchestrator.routing;

import com.org.llm.orchestrator.agent.AgentAction;
import com.org.llm.orchestrator.agent.PlannedAction;

/**
 * GoF <b>Strategy</b> — one implementation per {@link AgentAction}, dispatched by {@link
 * RoutingStrategyChain}. Mirrors llm-gateway-core's {@code LlmServiceProvider} SPI: adding a new
 * capability to the agent loop is "implement this interface and register a bean", no changes to the
 * executor or the chain.
 */
public interface RoutingStrategy {

  /** True when this strategy handles the given planned action. */
  boolean supports(AgentAction action);

  /** Executes the planned action and returns the observation fed back to the planner LLM. */
  StepResult execute(AgentContext context, PlannedAction plannedAction);
}
