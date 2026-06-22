package com.org.llm.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.orchestrator.client.GatewayClient;
import com.org.llm.orchestrator.client.dto.GatewayChatResponse;
import com.org.llm.orchestrator.persistence.AgentRunRepository;
import com.org.llm.orchestrator.routing.RoutingStrategyChain;
import com.org.llm.orchestrator.routing.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

class AgentLoopExecutorTest {

  private final GatewayClient gatewayClient = mock(GatewayClient.class);
  private final RoutingStrategyChain routingStrategyChain = mock(RoutingStrategyChain.class);
  private final ToolCallbackProvider toolCallbackProvider = mock(ToolCallbackProvider.class);
  private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
  private final AgentProperties agentProperties = new AgentProperties();

  private AgentLoopExecutor executor;

  @BeforeEach
  void setUp() {
    when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
    when(agentRunRepository.createRun(any(), anyString())).thenReturn(42L);
    executor =
        new AgentLoopExecutor(
            gatewayClient,
            routingStrategyChain,
            toolCallbackProvider,
            agentRunRepository,
            agentProperties,
            new ObjectMapper());
  }

  @Test
  @DisplayName(
      "a FINAL_ANSWER on the first planning turn ends the loop without any routing dispatch")
  void finalAnswerOnFirstTurnEndsLoopImmediately() {
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"42\",\"reasoning\":\"done\"}"));

    AgentRun run = executor.run("what is the answer?", "session-1");

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).isEqualTo("42");
    assertThat(run.steps()).isEmpty();
    verify(routingStrategyChain, never()).dispatch(any(), any());
    verify(agentRunRepository).complete(42L, AgentRunStatus.COMPLETED, "42");
  }

  @Test
  @DisplayName("a non-final action is dispatched and its observation feeds the next planning turn")
  void nonFinalActionIsDispatchedThenLoopContinues() {
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"GATEWAY_LLM\",\"toolName\":null,\"input\":\"sub-question\",\"reasoning\":\"r1\"}"))
        .thenReturn(
            chatResponse(
                "{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"final\",\"reasoning\":\"r2\"}"));
    when(routingStrategyChain.dispatch(any(), any())).thenReturn(StepResult.ok("observation-1"));

    AgentRun run = executor.run("question", "session-1");

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).isEqualTo("final");
    assertThat(run.steps()).hasSize(1);
    assertThat(run.steps().get(0).observation()).isEqualTo("observation-1");
    verify(routingStrategyChain, times(1)).dispatch(any(), any());
    verify(agentRunRepository).saveStep(any());
  }

  @Test
  @DisplayName("hitting agent.max-iterations without a final answer ends the run as INCOMPLETE")
  void maxIterationsExceededEndsAsIncomplete() {
    agentProperties.setMaxIterations(2);
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"GATEWAY_LLM\",\"toolName\":null,\"input\":\"x\",\"reasoning\":\"r\"}"));
    when(routingStrategyChain.dispatch(any(), any())).thenReturn(StepResult.ok("still working"));

    AgentRun run = executor.run("question", null);

    assertThat(run.status()).isEqualTo(AgentRunStatus.INCOMPLETE);
    assertThat(run.finalAnswer()).isEqualTo("still working");
    assertThat(run.steps()).hasSize(2);
    verify(agentRunRepository).complete(42L, AgentRunStatus.INCOMPLETE, "still working");
  }

  @Test
  @DisplayName(
      "unparseable planner output is treated as a final answer instead of crashing the loop")
  void unparseablePlannerOutputBecomesFinalAnswer() {
    when(gatewayClient.query(anyString(), anyString())).thenReturn(chatResponse("not json at all"));

    AgentRun run = executor.run("question", null);

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).isEqualTo("not json at all");
    verify(routingStrategyChain, never()).dispatch(any(), any());
  }

  @Test
  @DisplayName("a planner JSON wrapped in markdown fences is still parsed correctly")
  void markdownFencedPlannerOutputIsParsed() {
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "```json\n{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"ok\",\"reasoning\":\"r\"}\n```"));

    AgentRun run = executor.run("question", null);

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).isEqualTo("ok");
  }

  @Test
  @DisplayName(
      "a failed gateway call ends the run immediately with the error surfaced as the final answer")
  void gatewayErrorEndsRunImmediately() {
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            new GatewayChatResponse(
                null, null, "llm-gateway-core", "gateway unreachable", null, null, null, 0L, null));

    AgentRun run = executor.run("question", null);

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).contains("gateway unreachable");
    verify(routingStrategyChain, never()).dispatch(any(), any());
  }

  private static GatewayChatResponse chatResponse(String content) {
    return new GatewayChatResponse(content, "gpt-4o", "openai", null, 1, 1, 2, 5L, "req-id");
  }
}
