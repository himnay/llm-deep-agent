package com.org.llm.deepagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.client.GatewayClient;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import com.org.llm.deepagent.exception.AgentRunNotFoundException;
import com.org.llm.deepagent.exception.InvalidRunStateException;
import com.org.llm.deepagent.persistence.AgentRunRepository;
import com.org.llm.deepagent.persistence.AgentTaskRepository;
import com.org.llm.deepagent.routing.AgentContext;
import com.org.llm.deepagent.routing.RoutingStrategyChain;
import com.org.llm.deepagent.routing.StepResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

/**
 * The ReAct-style plan/act/observe loop. {@link #startRun} kicks a run off asynchronously; {@link
 * #continueRun(long)} is the single reentrant core — on every call it rebuilds the transcript from
 * persisted {@link AgentStep}s, asks the planner LLM what to do next, dispatches that decision
 * through the {@link RoutingStrategyChain}, and repeats — until the planner returns {@link
 * AgentAction#FINAL_ANSWER}, the run is paused on a gated action (see {@link #approve}/{@link
 * #reject}), or its iteration budget is exhausted. Because state is always read back from {@link
 * AgentRunRepository} rather than held in memory, a run survives being resumed from a completely
 * different request/thread (or after a restart).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoopExecutor {

  private final GatewayClient gatewayClient;
  private final RoutingStrategyChain routingStrategyChain;
  private final ToolCallbackProvider toolCallbackProvider;
  private final AgentRunRepository agentRunRepository;
  private final AgentTaskRepository agentTaskRepository;
  private final ContextCompactor contextCompactor;
  private final RunEventBroadcaster runEventBroadcaster;
  private final AgentProperties agentProperties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final Executor agentRunExecutor;

  /**
   * Creates a top-level run and submits it to {@link #continueRun} on the background executor,
   * returning immediately with status {@code RUNNING} — callers follow progress via {@code GET
   * /agent/run/{id}} (poll) or {@code GET /agent/run/{id}/events} (SSE).
   */
  public AgentRun startRun(String prompt, String sessionId) {
    long runId = agentRunRepository.createRun(sessionId, prompt);
    log.info("AGENT_LOOP | run={} | started", runId);
    agentRunExecutor.execute(() -> continueRun(runId));
    return agentRunRepository.findById(runId);
  }

  /**
   * Approves the action currently awaiting human review: dispatches it, persists it as a normal
   * step, and resumes the loop. Throws {@link InvalidRunStateException} if the run isn't currently
   * {@code AWAITING_APPROVAL}.
   */
  public AgentRun approve(long runId) {
    AgentRun run = requireAwaitingApproval(runId);
    AgentContext context = contextFor(run);
    AgentStep step = dispatchAndPersist(context, run.pendingAction(), run.steps().size());
    runEventBroadcaster.publish(runId, "step", step);
    agentRunRepository.clearPendingAction(runId);
    log.info(
        "AGENT_LOOP | run={} | action approved | action={}", runId, run.pendingAction().action());
    agentRunExecutor.execute(() -> continueRun(runId));
    return agentRunRepository.findById(runId);
  }

  /**
   * Rejects the action currently awaiting human review: instead of dispatching it, feeds the
   * planner a synthetic observation explaining the rejection and resumes the loop so it can adapt.
   */
  public AgentRun reject(long runId, String reason) {
    AgentRun run = requireAwaitingApproval(runId);
    PlannedAction rejected = run.pendingAction();
    String observation =
        "Action rejected by reviewer" + (reason == null || reason.isBlank() ? "." : ": " + reason);
    AgentStep step =
        new AgentStep(
            null,
            runId,
            run.steps().size(),
            rejected.action(),
            rejected.toolName(),
            rejected.input(),
            observation,
            rejected.reasoning(),
            Instant.now());
    agentRunRepository.saveStep(step);
    runEventBroadcaster.publish(runId, "step", step);
    agentRunRepository.clearPendingAction(runId);
    log.info("AGENT_LOOP | run={} | action rejected | action={}", runId, rejected.action());
    agentRunExecutor.execute(() -> continueRun(runId));
    return agentRunRepository.findById(runId);
  }

  /**
   * Runs a sub-task to completion synchronously, on the caller's thread — safe because the only
   * caller, {@code SubAgentRoutingStrategy}, is itself already running on the parent's background
   * executor, never an HTTP request thread. The nested run gets its own audit trail ({@code
   * parentRunId} set, {@code rootRunId} inherited) but is otherwise an ordinary run.
   */
  public AgentRun runSubAgentToCompletion(
      String prompt, String sessionId, long parentRunId, long rootRunId) {
    long runId = agentRunRepository.createRun(sessionId, prompt, parentRunId, rootRunId);
    log.info("AGENT_LOOP | run={} | sub-agent started | parent={}", runId, parentRunId);
    continueRun(runId);
    return agentRunRepository.findById(runId);
  }

  /**
   * The reentrant core loop body: re-derives all state for {@code runId} from the repository, then
   * plans/dispatches steps until a final answer, an approval gate, or the iteration budget. No-ops
   * (logging a warning) if the run isn't currently {@code RUNNING} — guards against a stale
   * re-submission racing a concurrent approve/reject.
   */
  void continueRun(long runId) {
    AgentRun run = agentRunRepository.findById(runId);
    if (run == null || run.status() != AgentRunStatus.RUNNING) {
      log.warn(
          "AGENT_LOOP | continueRun skipped for run={} | not RUNNING (status={})",
          runId,
          run == null ? "MISSING" : run.status());
      return;
    }

    AgentContext context = contextFor(run);
    int maxIterations =
        context.isSubAgent()
            ? agentProperties.getSubAgentMaxIterations()
            : agentProperties.getMaxIterations();
    List<AgentStep> steps = new ArrayList<>(run.steps());

    for (int i = steps.size(); i < maxIterations; i++) {
      PlannedAction plannedAction = plan(run, steps, context);

      if (plannedAction.action() == AgentAction.FINAL_ANSWER) {
        finish(runId, AgentRunStatus.COMPLETED, plannedAction.input());
        return;
      }

      if (agentProperties.getApprovalRequiredActions().contains(plannedAction.action())) {
        agentRunRepository.markAwaitingApproval(runId, plannedAction);
        runEventBroadcaster.publish(runId, "awaiting_approval", plannedAction);
        log.info(
            "AGENT_LOOP | run={} | paused for approval | action={}", runId, plannedAction.action());
        return;
      }

      AgentStep step = dispatchAndPersist(context, plannedAction, i);
      steps.add(step);
      runEventBroadcaster.publish(runId, "step", step);
    }

    String bestEffort =
        steps.isEmpty()
            ? "No progress could be made within the iteration budget."
            : steps.get(steps.size() - 1).observation();
    finish(runId, AgentRunStatus.INCOMPLETE, bestEffort);
  }

  private AgentRun requireAwaitingApproval(long runId) {
    AgentRun run = agentRunRepository.findById(runId);
    if (run == null) {
      throw new AgentRunNotFoundException(runId);
    }
    if (run.status() != AgentRunStatus.AWAITING_APPROVAL || run.pendingAction() == null) {
      throw new InvalidRunStateException(
          "Run " + runId + " is not awaiting approval (status=" + run.status() + ")");
    }
    return run;
  }

  private AgentContext contextFor(AgentRun run) {
    return new AgentContext(run.id(), run.rootRunId(), run.sessionId(), run.parentRunId() != null);
  }

  private AgentStep dispatchAndPersist(
      AgentContext context, PlannedAction plannedAction, int stepIndex) {
    StepResult result = routingStrategyChain.dispatch(context, plannedAction);
    AgentStep step =
        new AgentStep(
            null,
            context.runId(),
            stepIndex,
            plannedAction.action(),
            plannedAction.toolName(),
            plannedAction.input(),
            result.observation(),
            plannedAction.reasoning(),
            Instant.now());
    agentRunRepository.saveStep(step);
    return step;
  }

  private void finish(long runId, AgentRunStatus status, String finalAnswer) {
    agentRunRepository.complete(runId, status, finalAnswer);
    AgentRun finalRun = agentRunRepository.findById(runId);
    if (finalRun.createdAt() != null) {
      meterRegistry
          .timer("agent.loop.execution", "subAgent", String.valueOf(finalRun.parentRunId() != null))
          .record(Duration.between(finalRun.createdAt(), Instant.now()));
    }
    runEventBroadcaster.complete(runId, "done", finalRun);
    log.info("AGENT_LOOP | run={} | finished | status={}", runId, status);
  }

  private PlannedAction plan(AgentRun run, List<AgentStep> steps, AgentContext context) {
    String transcript = contextCompactor.buildTranscript(run, steps);
    List<AgentTask> tasks = agentTaskRepository.findByRootRunId(context.rootRunId());
    String systemPrompt = plannerSystemPrompt(context.isSubAgent(), tasks);
    String userPrompt =
        sessionHistory(run, context)
            + "User request: "
            + run.prompt()
            + "\n\nTranscript so far:\n"
            + (transcript.isBlank() ? "(none yet — this is the first step)" : transcript)
            + "\n\nWhat is the single next action?";

    GatewayChatResponse response = gatewayClient.query(userPrompt, systemPrompt);
    if (response.error() != null) {
      log.warn("AGENT_LOOP | planner call failed | {}", response.error());
      return new PlannedAction(
          AgentAction.FINAL_ANSWER,
          null,
          "I was unable to complete this request: " + response.error(),
          "planner call failed");
    }
    return parsePlannedAction(response.content());
  }

  private String sessionHistory(AgentRun run, AgentContext context) {
    if (context.isSubAgent() || run.sessionId() == null || run.sessionId().isBlank()) {
      return "";
    }
    List<AgentRun> priorRuns =
        agentRunRepository.findRecentBySession(
            run.sessionId(), run.id(), agentProperties.getSessionHistoryLimit());
    if (priorRuns.isEmpty()) {
      return "";
    }
    StringBuilder history =
        new StringBuilder("Conversation history for this session (most recent first):\n");
    for (AgentRun prior : priorRuns) {
      history
          .append("- Q: ")
          .append(prior.prompt())
          .append(" / A: ")
          .append(prior.finalAnswer())
          .append('\n');
    }
    return history.append('\n').toString();
  }

  private PlannedAction parsePlannedAction(String content) {
    try {
      return objectMapper.readValue(stripMarkdownFences(content), PlannedAction.class);
    } catch (Exception e) {
      log.warn(
          "AGENT_LOOP | could not parse planner output as JSON, treating it as the final answer | {}",
          e.getMessage());
      return new PlannedAction(
          AgentAction.FINAL_ANSWER, null, content, "fallback: unparseable planner output");
    }
  }

  private static String stripMarkdownFences(String content) {
    String trimmed = content == null ? "" : content.trim();
    if (trimmed.startsWith("```")) {
      trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
      if (trimmed.endsWith("```")) {
        trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
      }
    }
    return trimmed;
  }

  private String plannerSystemPrompt(boolean isSubAgent, List<AgentTask> tasks) {
    String toolCatalogue =
        Arrays.stream(toolCallbackProvider.getToolCallbacks())
            .map(
                tc ->
                    "- "
                        + tc.getToolDefinition().name()
                        + ": "
                        + tc.getToolDefinition().description())
            .collect(Collectors.joining("\n"));

    String actionEnum =
        "GATEWAY_LLM|RAG_RETRIEVE|RAG_GENERATE|MCP_TOOL|PLAN_TASKS|FILE_WRITE|FILE_READ"
            + (isSubAgent ? "" : "|DELEGATE_SUBAGENT")
            + "|FINAL_ANSWER";
    String delegateGuide =
        isSubAgent
            ? ""
            : "- DELEGATE_SUBAGENT: hand a self-contained sub-task to an isolated nested agent — set"
                + " \"input\" to the sub-task's full instructions. Only its final answer comes back to"
                + " you, so use it to keep your own transcript short when a sub-task needs many"
                + " exploratory steps.\n";

    return """
        You are the planning component of an autonomous agent. On every turn you choose exactly
        ONE next action and respond with STRICT JSON ONLY — no markdown fences, no commentary —
        matching exactly this shape:

        {"action": "%s", "toolName": null, "input": "...", "reasoning": "one short sentence"}

        Action guide:
        - GATEWAY_LLM: a plain LLM call (no external grounding needed) — set "input" to the prompt.
        - RAG_RETRIEVE: fetch grounded source chunks/citations without generating an answer — set "input" to the search query.
        - RAG_GENERATE: a full grounded answer with citations and a faithfulness check — set "input" to the question.
        - MCP_TOOL: invoke a named tool below — set "toolName" to its exact name and "input" to a JSON object string of its arguments.
        - PLAN_TASKS: replace the task list below with your full updated list — set "input" to a JSON array of {"taskKey":"...","description":"...","status":"PENDING|IN_PROGRESS|COMPLETED"}. Use this for any request with more than one moving part and keep it current as you make progress.
        - FILE_WRITE: save a note/finding for later — set "input" to {"path":"...","content":"..."}.
        - FILE_READ: read back a file you (or a sub-agent) wrote earlier — set "input" to {"path":"..."}.
        %s- FINAL_ANSWER: you have enough information — set "input" to the complete answer for the user. This ends the loop.

        Current task list:
        %s

        Available MCP tools:
        %s

        Respond with the JSON object and nothing else.
        """
        .formatted(
            actionEnum,
            delegateGuide,
            renderTasks(tasks),
            toolCatalogue.isBlank() ? "(none currently available)" : toolCatalogue);
  }

  private static String renderTasks(List<AgentTask> tasks) {
    if (tasks.isEmpty()) {
      return "(no tasks yet — use PLAN_TASKS to create one for any multi-step request)";
    }
    return tasks.stream()
        .map(t -> "- [" + t.status() + "] " + t.taskKey() + ": " + t.description())
        .collect(Collectors.joining("\n"));
  }
}
