package com.org.llm.orchestrator.web.dto;

import com.org.llm.orchestrator.agent.AgentRun;
import com.org.llm.orchestrator.agent.AgentRunStatus;
import java.time.Instant;
import java.util.List;

public record AgentRunResponse(
    Long runId,
    String sessionId,
    String prompt,
    AgentRunStatus status,
    String finalAnswer,
    List<AgentStepResponse> steps,
    Instant createdAt,
    Instant completedAt) {

  public static AgentRunResponse from(AgentRun run) {
    return new AgentRunResponse(
        run.id(),
        run.sessionId(),
        run.prompt(),
        run.status(),
        run.finalAnswer(),
        run.steps().stream().map(AgentStepResponse::from).toList(),
        run.createdAt(),
        run.completedAt());
  }
}
