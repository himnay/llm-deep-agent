package com.org.llm.deepagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.dto.*;
import com.org.llm.deepagent.client.GatewayClient;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import com.org.llm.deepagent.exception.AgentRunNotFoundException;
import com.org.llm.deepagent.exception.InvalidRunStateException;
import com.org.llm.deepagent.persistence.AgentRunRepository;
import com.org.llm.deepagent.persistence.AgentTaskRepository;
import com.org.llm.deepagent.persistence.ApprovalAuditRepository;
import com.org.llm.deepagent.routing.AgentContext;
import com.org.llm.deepagent.routing.RoutingStrategyChain;
import com.org.llm.deepagent.routing.StepResult;
import com.org.llm.deepagent.security.PromptInjectionGuard;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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
    private final ApprovalAuditRepository approvalAuditRepository;
    private final ContextCompactor contextCompactor;
    private final RunEventBroadcaster runEventBroadcaster;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Executor agentRunExecutor;
    private final PromptInjectionGuard injectionGuard;

    private String plannerSystemTemplate;

    @PostConstruct
    void loadPromptTemplates() {
        try {
            plannerSystemTemplate = new ClassPathResource("prompts/planner-system.st")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load prompts/planner-system.st", e);
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

    private static String renderTasks(List<AgentTask> tasks) {
        if (tasks.isEmpty()) {
            return "(no tasks yet — use PLAN_TASKS to create one for any multi-step request)";
        }
        return tasks.stream()
                .map(t -> "- [" + t.status() + "] " + t.taskKey() + ": " + t.description())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Creates a top-level run owned by {@code createdBy} and submits it to {@link #continueRun} on
     * the background executor, returning immediately with status {@code RUNNING} — callers follow
     * progress via {@code GET /agent/run/{id}} (poll) or {@code GET /agent/run/{id}/events} (SSE).
     */
    public AgentRun startRun(String prompt, String sessionId, String createdBy) {
        if (!injectionGuard.isQuerySafe(prompt)) {
            log.warn("AGENT_LOOP | injection guard rejected run | createdBy={}", createdBy);
            throw new InvalidRunStateException(
                    injectionGuard.blockMessage());
        }
        long runId = agentRunRepository.createRun(sessionId, prompt, null, null, createdBy);
        log.info("AGENT_LOOP | run={} | started | createdBy={}", runId, createdBy);
        agentRunExecutor.execute(() -> continueRun(runId));
        return agentRunRepository.findById(runId);
    }

    /**
     * Approves the action currently awaiting human review: dispatches it, persists it as a normal
     * step, records {@code actor} in the approval audit trail, and resumes the loop. Throws {@link
     * InvalidRunStateException} if the run isn't currently {@code AWAITING_APPROVAL}, or if a
     * concurrent approve/reject call already claimed it first — {@link
     * com.org.llm.deepagent.persistence.AgentRunRepository#claimPendingAction} guarantees only one
     * caller ever wins that race, so the pending action is never dispatched twice.
     */
    public AgentRun approve(long runId, String actor) {
        AgentRun run = requireAwaitingApproval(runId);
        if (!agentRunRepository.claimPendingAction(runId)) {
            throw new InvalidRunStateException(
                    "Run " + runId + " was already approved or rejected by a concurrent request");
        }
        int stepIndex = run.steps().size();
        approvalAuditRepository.record(runId, stepIndex, ApprovalDecision.APPROVED, actor, null);
        AgentContext context = contextFor(run);
        AgentStep step = dispatchAndPersist(context, run.pendingAction(), stepIndex);
        runEventBroadcaster.publish(runId, "step", step);
        log.info(
                "AGENT_LOOP | run={} | action approved | actor={} | action={}",
                runId,
                actor,
                run.pendingAction().action());
        agentRunExecutor.execute(() -> continueRun(runId));
        return agentRunRepository.findById(runId);
    }

    /**
     * Rejects the action currently awaiting human review: instead of dispatching it, feeds the
     * planner a synthetic observation explaining the rejection and resumes the loop so it can adapt.
     * Records {@code actor}/{@code reason} in the approval audit trail. Same concurrent-claim
     * guarantee as {@link #approve(long, String)}.
     */
    public AgentRun reject(long runId, String actor, String reason) {
        AgentRun run = requireAwaitingApproval(runId);
        if (!agentRunRepository.claimPendingAction(runId)) {
            throw new InvalidRunStateException(
                    "Run " + runId + " was already approved or rejected by a concurrent request");
        }
        int stepIndex = run.steps().size();
        approvalAuditRepository.record(runId, stepIndex, ApprovalDecision.REJECTED, actor, reason);
        PlannedAction rejected = run.pendingAction();
        String observation =
                "Action rejected by reviewer" + (reason == null || reason.isBlank() ? "." : ": " + reason);
        AgentStep step =
                new AgentStep(
                        null,
                        runId,
                        stepIndex,
                        rejected.action(),
                        rejected.toolName(),
                        rejected.input(),
                        observation,
                        rejected.reasoning(),
                        Instant.now());
        agentRunRepository.saveStep(step);
        runEventBroadcaster.publish(runId, "step", step);
        log.info(
                "AGENT_LOOP | run={} | action rejected | actor={} | action={}",
                runId,
                actor,
                rejected.action());
        agentRunExecutor.execute(() -> continueRun(runId));
        return agentRunRepository.findById(runId);
    }

    /**
     * Cancels a run if it's still {@code RUNNING}/{@code AWAITING_APPROVAL}; a no-op (returns the
     * current state unchanged) if it had already reached a terminal status — idempotent, so callers
     * don't need to check status before calling it.
     */
    public AgentRun cancel(long runId) {
        AgentRun run = agentRunRepository.findById(runId);
        if (run == null) {
            throw new AgentRunNotFoundException(runId);
        }
        if (!agentRunRepository.cancelIfNotTerminal(runId)) {
            return run;
        }
        AgentRun cancelled = agentRunRepository.findById(runId);
        runEventBroadcaster.complete(runId, "done", cancelled);
        log.info("AGENT_LOOP | run={} | cancelled", runId);
        return cancelled;
    }

    /**
     * Runs a sub-task to completion synchronously, on the caller's thread — safe because the only
     * caller, {@code SubAgentRoutingStrategy}, is itself already running on the parent's background
     * executor, never an HTTP request thread. The nested run gets its own audit trail ({@code
     * parentRunId} set, {@code rootRunId} and {@code createdBy} inherited from the parent) but is
     * otherwise an ordinary run.
     */
    public AgentRun runSubAgentToCompletion(
            String prompt, String sessionId, long parentRunId, long rootRunId) {
        AgentRun parent = agentRunRepository.findById(parentRunId);
        String createdBy = parent == null ? null : parent.createdBy();
        long runId = agentRunRepository.createRun(sessionId, prompt, parentRunId, rootRunId, createdBy);
        log.info("AGENT_LOOP | run={} | sub-agent started | parent={}", runId, parentRunId);
        continueRun(runId);
        return agentRunRepository.findById(runId);
    }

    /**
     * The reentrant core loop entry point: re-derives all state for {@code runId} from the
     * repository, then plans/dispatches steps until a final answer, an approval gate, or the
     * iteration budget. Runs on a background virtual thread with no caller waiting on it, so any
     * exception that escapes {@link #executeLoop} is caught here and turned into a {@code FAILED} run
     * instead of dying silently and leaving the row stuck at {@code RUNNING} forever.
     */
    void continueRun(long runId) {
        try {
            executeLoop(runId);
        } catch (Exception e) {
            log.error(
                    "AGENT_LOOP | run={} | unexpected failure, marking FAILED | {}",
                    runId,
                    e.getMessage(),
                    e);
            finish(
                    runId,
                    AgentRunStatus.FAILED,
                    "This run failed due to an unexpected internal error and could not continue. (run id: "
                            + runId
                            + ")");
        }
    }

    private void executeLoop(long runId) {
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
            // Re-read on every turn (not just once at entry) so a concurrent cancel() or a token budget
            // crossed by the last step's planner call is noticed before the next one is made.
            RunControlState controlState = agentRunRepository.findControlState(runId);
            if (controlState == null || controlState.status() != AgentRunStatus.RUNNING) {
                log.info(
                        "AGENT_LOOP | run={} | loop stopped externally | status={}",
                        runId,
                        controlState == null ? "MISSING" : controlState.status());
                return;
            }
            if (controlState.totalTokensUsed() >= agentProperties.getMaxTotalTokens()) {
                String bestEffort =
                        steps.isEmpty() ? "no progress made" : steps.get(steps.size() - 1).observation();
                finish(
                        runId,
                        AgentRunStatus.INCOMPLETE,
                        "Stopped after exceeding the token budget (agent.max-total-tokens="
                                + agentProperties.getMaxTotalTokens()
                                + "). Last observation: "
                                + bestEffort);
                return;
            }

            PlannedAction plannedAction = plan(run, steps, context);

            if (plannedAction.action() == AgentAction.FINAL_ANSWER) {
                finish(runId, AgentRunStatus.COMPLETED, plannedAction.input());
                return;
            }

            if (agentProperties.isApprovalRequired(plannedAction.action(), plannedAction.toolName())) {
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
        String observation = sanitizeObservation(context.runId(), stepIndex, result.observation());
        AgentStep step =
                new AgentStep(
                        null,
                        context.runId(),
                        stepIndex,
                        plannedAction.action(),
                        plannedAction.toolName(),
                        plannedAction.input(),
                        observation,
                        plannedAction.reasoning(),
                        Instant.now());
        agentRunRepository.saveStep(step);
        return step;
    }

    /**
     * Scans tool/RAG observations for prompt-injection patterns before they re-enter the planner
     * transcript. Attacker-controlled external data (MCP tool results, web pages) can carry
     * injection payloads — blocking them here prevents indirect prompt injection.
     */
    private String sanitizeObservation(long runId, int stepIndex, String observation) {
        if (observation == null || observation.isBlank()) {
            return observation;
        }
        if (!injectionGuard.isQuerySafe(observation)) {
            log.warn("AGENT_LOOP | injection pattern detected in tool observation | run={} step={}",
                    runId, stepIndex);
            return "[TOOL OUTPUT BLOCKED: observation contained a disallowed injection pattern]";
        }
        return observation;
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
        if (response.totalTokens() != null) {
            agentRunRepository.addTokensUsed(context.runId(), response.totalTokens());
        }
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

        ST st = new ST(plannerSystemTemplate, '$', '$');
        st.add("actionEnum", actionEnum);
        st.add("delegateGuide", delegateGuide);
        st.add("tasks", renderTasks(tasks));
        st.add("toolCatalogue", toolCatalogue.isBlank() ? "(none currently available)" : toolCatalogue);
        return st.render();
    }
}
