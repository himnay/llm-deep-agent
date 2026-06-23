package com.org.llm.deepagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.client.GatewayClient;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import com.org.llm.deepagent.persistence.AgentRunRepository;
import com.org.llm.deepagent.persistence.AgentTaskRepository;
import com.org.llm.deepagent.routing.RoutingStrategyChain;
import com.org.llm.deepagent.routing.StepResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

class AgentLoopExecutorTest {

  private final GatewayClient gatewayClient = mock(GatewayClient.class);
  private final RoutingStrategyChain routingStrategyChain = mock(RoutingStrategyChain.class);
  private final ToolCallbackProvider toolCallbackProvider = mock(ToolCallbackProvider.class);
  private final FakeAgentRunRepository agentRunRepository = new FakeAgentRunRepository();
  private final AgentTaskRepository agentTaskRepository = mock(AgentTaskRepository.class);
  private final RunEventBroadcaster runEventBroadcaster = mock(RunEventBroadcaster.class);
  private final AgentProperties agentProperties = new AgentProperties();
  // Synchronous "executor": runs the submitted loop inline, so startRun()/approve()/reject() return
  // the fully-settled run rather than just the initial RUNNING snapshot — exactly what these tests
  // need.
  private final Executor synchronousExecutor = Runnable::run;

  private AgentLoopExecutor executor;

  @BeforeEach
  void setUp() {
    when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
    ContextCompactor contextCompactor =
        new ContextCompactor(gatewayClient, agentRunRepository, agentProperties);
    executor =
        new AgentLoopExecutor(
            gatewayClient,
            routingStrategyChain,
            toolCallbackProvider,
            agentRunRepository,
            agentTaskRepository,
            contextCompactor,
            runEventBroadcaster,
            agentProperties,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            synchronousExecutor);
  }

  @Test
  @DisplayName(
      "a FINAL_ANSWER on the first planning turn ends the loop without any routing dispatch")
  void finalAnswerOnFirstTurnEndsLoopImmediately() {
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"42\",\"reasoning\":\"done\"}"));

    AgentRun run = executor.startRun("what is the answer?", "session-1");

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).isEqualTo("42");
    assertThat(run.steps()).isEmpty();
    verify(routingStrategyChain, never()).dispatch(any(), any());
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

    AgentRun run = executor.startRun("question", "session-1");

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).isEqualTo("final");
    assertThat(run.steps()).hasSize(1);
    assertThat(run.steps().get(0).observation()).isEqualTo("observation-1");
    verify(routingStrategyChain, times(1)).dispatch(any(), any());
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

    AgentRun run = executor.startRun("question", null);

    assertThat(run.status()).isEqualTo(AgentRunStatus.INCOMPLETE);
    assertThat(run.finalAnswer()).isEqualTo("still working");
    assertThat(run.steps()).hasSize(2);
  }

  @Test
  @DisplayName(
      "unparseable planner output is treated as a final answer instead of crashing the loop")
  void unparseablePlannerOutputBecomesFinalAnswer() {
    when(gatewayClient.query(anyString(), anyString())).thenReturn(chatResponse("not json at all"));

    AgentRun run = executor.startRun("question", null);

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

    AgentRun run = executor.startRun("question", null);

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

    AgentRun run = executor.startRun("question", null);

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).contains("gateway unreachable");
    verify(routingStrategyChain, never()).dispatch(any(), any());
  }

  @Test
  @DisplayName(
      "an action in agent.approval-required-actions pauses the run instead of dispatching it")
  void gatedActionPausesForApproval() {
    agentProperties.setApprovalRequiredActions(EnumSet.of(AgentAction.MCP_TOOL));
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"MCP_TOOL\",\"toolName\":\"deploy\",\"input\":\"{}\",\"reasoning\":\"r\"}"));

    AgentRun run = executor.startRun("deploy it", null);

    assertThat(run.status()).isEqualTo(AgentRunStatus.AWAITING_APPROVAL);
    assertThat(run.pendingAction().toolName()).isEqualTo("deploy");
    assertThat(run.steps()).isEmpty();
    verify(routingStrategyChain, never()).dispatch(any(), any());
  }

  @Test
  @DisplayName("approve() dispatches the pending action and resumes the loop to completion")
  void approveDispatchesPendingActionAndResumes() {
    agentProperties.setApprovalRequiredActions(EnumSet.of(AgentAction.MCP_TOOL));
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"MCP_TOOL\",\"toolName\":\"deploy\",\"input\":\"{}\",\"reasoning\":\"r\"}"))
        .thenReturn(
            chatResponse(
                "{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"deployed\",\"reasoning\":\"r2\"}"));
    when(routingStrategyChain.dispatch(any(), any()))
        .thenReturn(StepResult.ok("deployment started"));
    AgentRun paused = executor.startRun("deploy it", null);

    AgentRun resumed = executor.approve(paused.id());

    assertThat(resumed.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(resumed.finalAnswer()).isEqualTo("deployed");
    assertThat(resumed.steps()).hasSize(1);
    assertThat(resumed.steps().get(0).observation()).isEqualTo("deployment started");
    verify(routingStrategyChain, times(1)).dispatch(any(), any());
  }

  @Test
  @DisplayName("reject() feeds the rejection back as an observation instead of dispatching")
  void rejectFeedsBackObservationAndResumes() {
    agentProperties.setApprovalRequiredActions(EnumSet.of(AgentAction.MCP_TOOL));
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"MCP_TOOL\",\"toolName\":\"deploy\",\"input\":\"{}\",\"reasoning\":\"r\"}"))
        .thenReturn(
            chatResponse(
                "{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"cancelled\",\"reasoning\":\"r2\"}"));
    AgentRun paused = executor.startRun("deploy it", null);

    AgentRun resumed = executor.reject(paused.id(), "too risky");

    assertThat(resumed.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(resumed.steps()).hasSize(1);
    assertThat(resumed.steps().get(0).observation()).contains("rejected").contains("too risky");
    verify(routingStrategyChain, never()).dispatch(any(), any());
  }

  @Test
  @DisplayName(
      "runSubAgentToCompletion() creates a nested run whose planner prompt omits DELEGATE_SUBAGENT")
  void subAgentRunOmitsDelegationFromItsOwnPrompt() {
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"sub-answer\",\"reasoning\":\"r\"}"));

    AgentRun subRun = executor.runSubAgentToCompletion("sub task", "session-1", 1L, 1L);

    assertThat(subRun.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(subRun.finalAnswer()).isEqualTo("sub-answer");
    assertThat(subRun.parentRunId()).isEqualTo(1L);
    assertThat(subRun.rootRunId()).isEqualTo(1L);

    ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
    verify(gatewayClient).query(anyString(), systemPromptCaptor.capture());
    assertThat(systemPromptCaptor.getValue()).doesNotContain("DELEGATE_SUBAGENT");
  }

  @Test
  @DisplayName("a top-level run's planner prompt offers DELEGATE_SUBAGENT")
  void topLevelRunOffersDelegation() {
    when(gatewayClient.query(anyString(), anyString()))
        .thenReturn(
            chatResponse(
                "{\"action\":\"FINAL_ANSWER\",\"toolName\":null,\"input\":\"ok\",\"reasoning\":\"r\"}"));

    executor.startRun("question", null);

    ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
    verify(gatewayClient).query(anyString(), systemPromptCaptor.capture());
    assertThat(systemPromptCaptor.getValue()).contains("DELEGATE_SUBAGENT");
  }

  private static GatewayChatResponse chatResponse(String content) {
    return new GatewayChatResponse(content, "gpt-4o", "openai", null, 1, 1, 2, 5L, "req-id");
  }

  /**
   * In-memory stand-in for {@link AgentRunRepository} — overrides every method so the JDBC-backed
   * superclass fields are never touched, giving {@link AgentLoopExecutor}'s resumable,
   * DB-reconstructed-transcript design something real to read/write across startRun/continueRun/
   * approve/reject calls within a single test.
   */
  private static final class FakeAgentRunRepository extends AgentRunRepository {
    private final Map<Long, AgentRun> runs = new HashMap<>();
    private final Map<Long, List<AgentStep>> stepsByRunId = new HashMap<>();
    private long nextId = 1;

    FakeAgentRunRepository() {
      super(null, null);
    }

    @Override
    public long createRun(String sessionId, String prompt) {
      return createRun(sessionId, prompt, null, null);
    }

    @Override
    public long createRun(String sessionId, String prompt, Long parentRunId, Long rootRunId) {
      long id = nextId++;
      long root = rootRunId != null ? rootRunId : id;
      runs.put(
          id,
          new AgentRun(
              id,
              sessionId,
              prompt,
              AgentRunStatus.RUNNING,
              null,
              List.of(),
              Instant.now(),
              null,
              parentRunId,
              root,
              null,
              0,
              null));
      stepsByRunId.put(id, new ArrayList<>());
      return id;
    }

    @Override
    public void saveStep(AgentStep step) {
      stepsByRunId.get(step.runId()).add(step);
    }

    @Override
    public void complete(long runId, AgentRunStatus status, String finalAnswer) {
      AgentRun run = runs.get(runId);
      runs.put(runId, withStatus(run, status, finalAnswer, null));
    }

    @Override
    public void markAwaitingApproval(long runId, PlannedAction pendingAction) {
      AgentRun run = runs.get(runId);
      runs.put(
          runId,
          withStatus(run, AgentRunStatus.AWAITING_APPROVAL, run.finalAnswer(), pendingAction));
    }

    @Override
    public void clearPendingAction(long runId) {
      AgentRun run = runs.get(runId);
      runs.put(runId, withStatus(run, AgentRunStatus.RUNNING, run.finalAnswer(), null));
    }

    @Override
    public void updateContextSummary(long runId, String summary, int summarizedStepCount) {
      AgentRun run = runs.get(runId);
      runs.put(
          runId,
          new AgentRun(
              run.id(),
              run.sessionId(),
              run.prompt(),
              run.status(),
              run.finalAnswer(),
              run.steps(),
              run.createdAt(),
              run.completedAt(),
              run.parentRunId(),
              run.rootRunId(),
              summary,
              summarizedStepCount,
              run.pendingAction()));
    }

    @Override
    public AgentRun findById(long runId) {
      AgentRun run = runs.get(runId);
      if (run == null) {
        return null;
      }
      return new AgentRun(
          run.id(),
          run.sessionId(),
          run.prompt(),
          run.status(),
          run.finalAnswer(),
          List.copyOf(stepsByRunId.get(runId)),
          run.createdAt(),
          run.completedAt(),
          run.parentRunId(),
          run.rootRunId(),
          run.contextSummary(),
          run.summarizedStepCount(),
          run.pendingAction());
    }

    @Override
    public List<AgentStep> findStepsByRunId(long runId) {
      return List.copyOf(stepsByRunId.get(runId));
    }

    @Override
    public List<AgentRun> findRecentBySession(String sessionId, long excludeRunId, int limit) {
      return List.of();
    }

    private static AgentRun withStatus(
        AgentRun run, AgentRunStatus status, String finalAnswer, PlannedAction pendingAction) {
      return new AgentRun(
          run.id(),
          run.sessionId(),
          run.prompt(),
          status,
          finalAnswer,
          run.steps(),
          run.createdAt(),
          status == AgentRunStatus.RUNNING || status == AgentRunStatus.AWAITING_APPROVAL
              ? run.completedAt()
              : Instant.now(),
          run.parentRunId(),
          run.rootRunId(),
          run.contextSummary(),
          run.summarizedStepCount(),
          pendingAction);
    }
  }
}
