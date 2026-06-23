package com.org.llm.deepagent.web.dto;

import com.org.llm.deepagent.agent.AgentTask;
import com.org.llm.deepagent.agent.AgentTaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "One entry of the planner-maintained task list for a run tree.")
public record AgentTaskResponse(
    @Schema(description = "Stable short identifier for this task.", example = "check-deploy-status")
        String taskKey,
    @Schema(description = "Human-readable description of the task.") String description,
    @Schema(description = "Current lifecycle state of the task.") AgentTaskStatus status,
    @Schema(description = "When this task was last created/updated.") Instant updatedAt) {

  /** Maps the domain {@link AgentTask} to its API representation. */
  public static AgentTaskResponse from(AgentTask task) {
    return new AgentTaskResponse(
        task.taskKey(), task.description(), task.status(), task.updatedAt());
  }
}
