package com.org.llm.orchestrator.web;

import com.org.llm.orchestrator.agent.AgentLoopExecutor;
import com.org.llm.orchestrator.agent.AgentRun;
import com.org.llm.orchestrator.exception.AgentRunNotFoundException;
import com.org.llm.orchestrator.persistence.AgentRunRepository;
import com.org.llm.orchestrator.web.dto.AgentRunRequest;
import com.org.llm.orchestrator.web.dto.AgentRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /agent/run} — kick off and inspect agentic orchestration runs. */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Tag(name = "Agent")
public class AgentController {

  private final AgentLoopExecutor agentLoopExecutor;
  private final AgentRunRepository agentRunRepository;

  @PostMapping("/run")
  @Operation(
      operationId = "runAgent",
      summary = "Run the agent loop to completion",
      description =
          "Executes the plan/act/observe loop (chaining llm-gateway, llm-rag and llm-mcp tools as the"
              + " planner decides) until a final answer is produced or agent.max-iterations is hit."
              + " Blocks for the full duration of the run.")
  public AgentRunResponse run(@Valid @RequestBody AgentRunRequest request) {
    log.info("AGENT | run requested | sessionId={}", request.sessionId());
    AgentRun run = agentLoopExecutor.run(request.prompt(), request.sessionId());
    return AgentRunResponse.from(run);
  }

  @GetMapping("/run/{runId}")
  @Operation(
      operationId = "getAgentRun",
      summary = "Fetch a past run's full step trace",
      description =
          "Returns the persisted plan/act/observe trace for a previously completed or in-progress run.")
  public AgentRunResponse getRun(
      @Parameter(description = "Numeric agent run id") @PathVariable long runId) {
    AgentRun run = agentRunRepository.findById(runId);
    if (run == null) {
      throw new AgentRunNotFoundException(runId);
    }
    return AgentRunResponse.from(run);
  }
}
