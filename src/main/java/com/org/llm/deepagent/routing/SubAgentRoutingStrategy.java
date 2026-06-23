package com.org.llm.deepagent.routing;

import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentLoopExecutor;
import com.org.llm.deepagent.agent.AgentRun;
import com.org.llm.deepagent.agent.PlannedAction;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Dispatches {@link AgentAction#DELEGATE_SUBAGENT} steps: runs {@code input} as an isolated, nested
 * agent run (context quarantine — only its final answer enters the parent's transcript, not its
 * intermediate steps) and reports the outcome back as this step's observation.
 */
@Component
@RequiredArgsConstructor
public class SubAgentRoutingStrategy implements RoutingStrategy {

  // ObjectProvider defers resolving AgentLoopExecutor until execute() actually needs it, breaking
  // the circular bean dependency: AgentLoopExecutor -> RoutingStrategyChain -> this strategy ->
  // AgentLoopExecutor.
  private final ObjectProvider<AgentLoopExecutor> agentLoopExecutorProvider;

  @Override
  public boolean supports(AgentAction action) {
    return action == AgentAction.DELEGATE_SUBAGENT;
  }

  @Override
  public StepResult execute(AgentContext context, PlannedAction plannedAction) {
    if (context.isSubAgent()) {
      return StepResult.error(
          "Sub-agents cannot delegate further (maximum delegation depth is 1).");
    }

    AgentRun subAgentRun =
        agentLoopExecutorProvider
            .getObject()
            .runSubAgentToCompletion(
                plannedAction.input(), context.sessionId(), context.runId(), context.rootRunId());

    return switch (subAgentRun.status()) {
      case COMPLETED -> StepResult.ok(subAgentRun.finalAnswer());
      case INCOMPLETE ->
          StepResult.ok(
              "Sub-agent did not finish within its iteration budget. Best-effort result: "
                  + subAgentRun.finalAnswer());
      case AWAITING_APPROVAL ->
          StepResult.error(
              "Sub-agent run "
                  + subAgentRun.id()
                  + " is awaiting human approval for "
                  + subAgentRun.pendingAction().action()
                  + " before it can continue.");
      case FAILED, RUNNING ->
          StepResult.error("Sub-agent run failed: " + subAgentRun.finalAnswer());
    };
  }
}
