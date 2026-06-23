package com.org.llm.deepagent.web;

import com.org.llm.deepagent.agent.AgentLoopExecutor;
import com.org.llm.deepagent.agent.AgentRun;
import com.org.llm.deepagent.agent.AgentTask;
import com.org.llm.deepagent.agent.RunEventBroadcaster;
import com.org.llm.deepagent.exception.AgentArtifactNotFoundException;
import com.org.llm.deepagent.exception.AgentRunNotFoundException;
import com.org.llm.deepagent.persistence.AgentArtifactRepository;
import com.org.llm.deepagent.persistence.AgentRunRepository;
import com.org.llm.deepagent.persistence.AgentTaskRepository;
import com.org.llm.deepagent.web.dto.AgentArtifactResponse;
import com.org.llm.deepagent.web.dto.AgentRunRequest;
import com.org.llm.deepagent.web.dto.AgentRunResponse;
import com.org.llm.deepagent.web.dto.RunRejectionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code /agent/run} — kick off, follow, approve/reject, and inspect agentic orchestration runs.
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Tag(name = "Agent")
public class AgentController {

  private final AgentLoopExecutor agentLoopExecutor;
  private final AgentRunRepository agentRunRepository;
  private final AgentTaskRepository agentTaskRepository;
  private final AgentArtifactRepository agentArtifactRepository;
  private final RunEventBroadcaster runEventBroadcaster;

  /**
   * Starts a new top-level run; it executes in the background — see the class-level Javadoc for how
   * to follow it.
   */
  @PostMapping("/run")
  @Operation(
      operationId = "runAgent",
      summary = "Start the agent loop",
      description =
          "Persists a new run and starts the plan/act/observe loop on a background thread, returning"
              + " immediately with status RUNNING. Follow progress via GET /agent/run/{id} (poll) or"
              + " GET /agent/run/{id}/events (SSE).")
  public AgentRunResponse run(@Valid @RequestBody AgentRunRequest request) {
    log.info("AGENT | run requested | sessionId={}", request.sessionId());
    AgentRun run = agentLoopExecutor.startRun(request.prompt(), request.sessionId());
    return toResponse(run);
  }

  /** Fetches the durable, persisted state of a run — safe to poll at any point in its lifecycle. */
  @GetMapping("/run/{runId}")
  @Operation(
      operationId = "getAgentRun",
      summary = "Fetch a run's current state and full step trace",
      description =
          "Returns the persisted plan/act/observe trace for a run, whatever its current status.")
  public AgentRunResponse getRun(
      @Parameter(description = "Numeric agent run id") @PathVariable long runId) {
    return toResponse(requireRun(runId));
  }

  /**
   * Subscribes to live Server-Sent Events ({@code step}, {@code awaiting_approval}, {@code done})
   * for a run.
   */
  @GetMapping(value = "/run/{runId}/events", produces = "text/event-stream")
  @Operation(
      operationId = "streamAgentRun",
      summary = "Stream live progress events for a run",
      description =
          "Server-Sent Events: a 'step' event after each persisted step, an 'awaiting_approval' event"
              + " when the run pauses for human review, and a terminal 'done' event. Only events"
              + " published while connected are received — use GET /agent/run/{id} for durable state.")
  public SseEmitter streamRun(
      @Parameter(description = "Numeric agent run id") @PathVariable long runId) {
    requireRun(runId);
    return runEventBroadcaster.subscribe(runId);
  }

  /** Approves the action a run is currently paused on, then resumes the loop. */
  @PostMapping("/run/{runId}/approve")
  @Operation(
      operationId = "approveAgentRun",
      summary = "Approve the pending action and resume",
      description =
          "Only valid while status is AWAITING_APPROVAL; otherwise responds 409 Conflict.")
  public AgentRunResponse approve(
      @Parameter(description = "Numeric agent run id") @PathVariable long runId) {
    return toResponse(agentLoopExecutor.approve(runId));
  }

  /** Rejects the action a run is currently paused on, feeding the rejection back to the planner. */
  @PostMapping("/run/{runId}/reject")
  @Operation(
      operationId = "rejectAgentRun",
      summary = "Reject the pending action and resume",
      description =
          "Only valid while status is AWAITING_APPROVAL; otherwise responds 409 Conflict. The"
              + " planner receives the rejection (and reason, if given) as the step's observation and"
              + " gets to choose a different next action.")
  public AgentRunResponse reject(
      @Parameter(description = "Numeric agent run id") @PathVariable long runId,
      @RequestBody(required = false) RunRejectionRequest request) {
    String reason = request == null ? null : request.reason();
    return toResponse(agentLoopExecutor.reject(runId, reason));
  }

  /** Fetches the full content of one scratchpad file belonging to a run's tree. */
  @GetMapping("/run/{runId}/files/{path}")
  @Operation(
      operationId = "getAgentRunFile",
      summary = "Fetch one scratchpad file's content",
      description =
          "Content is kept out of GET /agent/run/{id} (which only lists paths) since files can be large.")
  public AgentArtifactResponse getFile(
      @Parameter(description = "Numeric agent run id") @PathVariable long runId,
      @Parameter(description = "Scratchpad file path") @PathVariable String path) {
    AgentRun run = requireRun(runId);
    return agentArtifactRepository
        .findByRootRunIdAndPath(run.rootRunId(), path)
        .map(AgentArtifactResponse::from)
        .orElseThrow(() -> new AgentArtifactNotFoundException(run.rootRunId(), path));
  }

  private AgentRun requireRun(long runId) {
    AgentRun run = agentRunRepository.findById(runId);
    if (run == null) {
      throw new AgentRunNotFoundException(runId);
    }
    return run;
  }

  private AgentRunResponse toResponse(AgentRun run) {
    List<AgentTask> tasks = agentTaskRepository.findByRootRunId(run.rootRunId());
    var files = agentArtifactRepository.listByRootRunId(run.rootRunId());
    return AgentRunResponse.from(run, tasks, files);
  }
}
