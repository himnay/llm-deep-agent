package com.org.llm.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.orchestrator.client.GatewayClient;
import com.org.llm.orchestrator.client.dto.GatewayChatResponse;
import com.org.llm.orchestrator.persistence.AgentRunRepository;
import com.org.llm.orchestrator.routing.AgentContext;
import com.org.llm.orchestrator.routing.RoutingStrategyChain;
import com.org.llm.orchestrator.routing.StepResult;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

/**
 * The ReAct-style plan/act/observe loop: on each iteration, asks the planner LLM (via {@link
 * GatewayClient}) what to do next, dispatches that decision through the {@link
 * RoutingStrategyChain}, and feeds the resulting observation back into the next planning prompt —
 * until the planner returns {@link AgentAction#FINAL_ANSWER} or {@code agent.max-iterations} is
 * reached.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoopExecutor {

  private final GatewayClient gatewayClient;
  private final RoutingStrategyChain routingStrategyChain;
  private final ToolCallbackProvider toolCallbackProvider;
  private final AgentRunRepository agentRunRepository;
  private final AgentProperties agentProperties;
  private final ObjectMapper objectMapper;

  @Timed(
      value = "agent.loop.execution",
      description = "End-to-end turnaround time of a single agent run",
      histogram = true)
  public AgentRun run(String prompt, String sessionId) {
    long runId = agentRunRepository.createRun(sessionId, prompt);
    List<AgentStep> steps = new ArrayList<>();
    StringBuilder transcript = new StringBuilder();
    Instant createdAt = Instant.now();

    for (int i = 0; i < agentProperties.getMaxIterations(); i++) {
      PlannedAction plannedAction = plan(prompt, transcript.toString());

      if (plannedAction.action() == AgentAction.FINAL_ANSWER) {
        agentRunRepository.complete(runId, AgentRunStatus.COMPLETED, plannedAction.input());
        log.info("AGENT_LOOP | run={} | completed in {} steps", runId, steps.size());
        return new AgentRun(
            runId,
            sessionId,
            prompt,
            AgentRunStatus.COMPLETED,
            plannedAction.input(),
            steps,
            createdAt,
            Instant.now());
      }

      StepResult result =
          routingStrategyChain.dispatch(new AgentContext(runId, sessionId), plannedAction);
      AgentStep step =
          new AgentStep(
              null,
              runId,
              i,
              plannedAction.action(),
              plannedAction.toolName(),
              plannedAction.input(),
              result.observation(),
              plannedAction.reasoning(),
              Instant.now());
      steps.add(step);
      agentRunRepository.saveStep(step);
      appendToTranscript(transcript, i, plannedAction, result);
    }

    String bestEffort =
        steps.isEmpty()
            ? "No progress could be made within the iteration budget."
            : steps.get(steps.size() - 1).observation();
    agentRunRepository.complete(runId, AgentRunStatus.INCOMPLETE, bestEffort);
    log.warn(
        "AGENT_LOOP | run={} | hit max-iterations ({}) without a final answer",
        runId,
        agentProperties.getMaxIterations());
    return new AgentRun(
        runId,
        sessionId,
        prompt,
        AgentRunStatus.INCOMPLETE,
        bestEffort,
        steps,
        createdAt,
        Instant.now());
  }

  private void appendToTranscript(
      StringBuilder transcript, int stepIndex, PlannedAction plannedAction, StepResult result) {
    transcript
        .append("Step ")
        .append(stepIndex + 1)
        .append(": action=")
        .append(plannedAction.action())
        .append(plannedAction.toolName() != null ? " tool=" + plannedAction.toolName() : "")
        .append(" input=")
        .append(plannedAction.input())
        .append("\nobservation: ")
        .append(result.observation())
        .append('\n');
  }

  private PlannedAction plan(String originalPrompt, String transcriptSoFar) {
    String systemPrompt = plannerSystemPrompt();
    String userPrompt =
        "User request: "
            + originalPrompt
            + "\n\nTranscript so far:\n"
            + (transcriptSoFar.isBlank() ? "(none yet — this is the first step)" : transcriptSoFar)
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

  private String plannerSystemPrompt() {
    String toolCatalogue =
        Arrays.stream(toolCallbackProvider.getToolCallbacks())
            .map(
                tc ->
                    "- "
                        + tc.getToolDefinition().name()
                        + ": "
                        + tc.getToolDefinition().description())
            .collect(Collectors.joining("\n"));

    return """
        You are the planning component of an autonomous agent. On every turn you choose exactly
        ONE next action and respond with STRICT JSON ONLY — no markdown fences, no commentary —
        matching exactly this shape:

        {"action": "GATEWAY_LLM|RAG_RETRIEVE|RAG_GENERATE|MCP_TOOL|FINAL_ANSWER", "toolName": null, "input": "...", "reasoning": "one short sentence"}

        Action guide:
        - GATEWAY_LLM: a plain LLM call (no external grounding needed) — set "input" to the prompt.
        - RAG_RETRIEVE: fetch grounded source chunks/citations without generating an answer — set "input" to the search query.
        - RAG_GENERATE: a full grounded answer with citations and a faithfulness check — set "input" to the question.
        - MCP_TOOL: invoke a named tool below — set "toolName" to its exact name and "input" to a JSON object string of its arguments.
        - FINAL_ANSWER: you have enough information — set "input" to the complete answer for the user. This ends the loop.

        Available MCP tools:
        %s

        Respond with the JSON object and nothing else.
        """
        .formatted(toolCatalogue.isBlank() ? "(none currently available)" : toolCatalogue);
  }
}
