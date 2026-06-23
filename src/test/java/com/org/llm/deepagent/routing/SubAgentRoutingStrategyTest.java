package com.org.llm.deepagent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentLoopExecutor;
import com.org.llm.deepagent.agent.AgentRun;
import com.org.llm.deepagent.agent.AgentRunStatus;
import com.org.llm.deepagent.agent.PlannedAction;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SubAgentRoutingStrategyTest {

  private final AgentLoopExecutor agentLoopExecutor = mock(AgentLoopExecutor.class);
  private final ObjectProvider<AgentLoopExecutor> provider = mock(ObjectProvider.class);
  private final SubAgentRoutingStrategy strategy = new SubAgentRoutingStrategy(provider);

  @Test
  @DisplayName("supports() only matches DELEGATE_SUBAGENT")
  void supportsOnlyDelegateSubagent() {
    assertThat(strategy.supports(AgentAction.DELEGATE_SUBAGENT)).isTrue();
    assertThat(strategy.supports(AgentAction.GATEWAY_LLM)).isFalse();
  }

  @Test
  @DisplayName("execute() refuses to delegate further when already running inside a sub-agent")
  void executeRefusesNestedDelegation() {
    StepResult result =
        strategy.execute(
            new AgentContext(5L, 1L, null, true),
            new PlannedAction(AgentAction.DELEGATE_SUBAGENT, null, "do more", null));

    assertThat(result.success()).isFalse();
    assertThat(result.observation()).contains("maximum delegation depth");
    verify(provider, never()).getObject();
  }

  @Test
  @DisplayName("execute() returns the sub-agent's final answer when it completes")
  void executeReturnsFinalAnswerOnCompletion() {
    when(provider.getObject()).thenReturn(agentLoopExecutor);
    when(agentLoopExecutor.runSubAgentToCompletion("do more", "s1", 1L, 1L))
        .thenReturn(completedRun(2L, "sub-agent answer"));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, 1L, "s1", false),
            new PlannedAction(AgentAction.DELEGATE_SUBAGENT, null, "do more", null));

    assertThat(result.success()).isTrue();
    assertThat(result.observation()).isEqualTo("sub-agent answer");
  }

  @Test
  @DisplayName("execute() surfaces a sub-agent stuck awaiting approval as an error observation")
  void executeSurfacesAwaitingApproval() {
    when(provider.getObject()).thenReturn(agentLoopExecutor);
    AgentRun awaiting =
        new AgentRun(
            2L,
            "s1",
            "do more",
            AgentRunStatus.AWAITING_APPROVAL,
            null,
            List.of(),
            Instant.now(),
            null,
            1L,
            1L,
            null,
            0,
            new PlannedAction(AgentAction.MCP_TOOL, "deploy", "{}", "r"));
    when(agentLoopExecutor.runSubAgentToCompletion("do more", "s1", 1L, 1L)).thenReturn(awaiting);

    StepResult result =
        strategy.execute(
            new AgentContext(1L, 1L, "s1", false),
            new PlannedAction(AgentAction.DELEGATE_SUBAGENT, null, "do more", null));

    assertThat(result.success()).isFalse();
    assertThat(result.observation()).contains("awaiting human approval").contains("MCP_TOOL");
  }

  private static AgentRun completedRun(long id, String finalAnswer) {
    return new AgentRun(
        id,
        "s1",
        "do more",
        AgentRunStatus.COMPLETED,
        finalAnswer,
        List.of(),
        Instant.now(),
        Instant.now(),
        1L,
        1L,
        null,
        0,
        null);
  }
}
