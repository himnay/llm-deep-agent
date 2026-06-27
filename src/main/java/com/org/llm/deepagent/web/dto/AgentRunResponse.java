package com.org.llm.deepagent.web.dto;

import com.org.llm.deepagent.agent.dto.AgentArtifact;
import com.org.llm.deepagent.agent.dto.AgentRun;
import com.org.llm.deepagent.agent.dto.AgentRunStatus;
import com.org.llm.deepagent.agent.dto.AgentTask;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "The current state of one agent run.")
public record AgentRunResponse(
        @Schema(description = "Numeric id of this run.") Long runId,
        @Schema(description = "Caller-supplied conversation identifier, if any.") String sessionId,
        @Schema(description = "The original task/question this run was started with.") String prompt,
        @Schema(description = "Current lifecycle state of the run.") AgentRunStatus status,
        @Schema(description = "The planner's final answer once status is COMPLETED/INCOMPLETE.")
        String finalAnswer,
        @Schema(description = "Every plan/act/observe step taken so far, in order.")
        List<AgentStepResponse> steps,
        @Schema(description = "When this run was created.") Instant createdAt,
        @Schema(description = "When this run reached a terminal status, if it has.")
        Instant completedAt,
        @Schema(description = "Set when this run is a sub-agent delegated by DELEGATE_SUBAGENT.")
        Long parentRunId,
        @Schema(description = "The top-level run id shared by this run and all of its sub-agents.")
        Long rootRunId,
        @Schema(
                description =
                        "The action awaiting approval, set only when status is AWAITING_APPROVAL.")
        PlannedActionResponse pendingAction,
        @Schema(description = "The run tree's current planner-maintained task list.")
        List<AgentTaskResponse> tasks,
        @Schema(description = "The run tree's current scratchpad files (paths only, no content).")
        List<AgentArtifactSummaryResponse> files) {

    /**
     * Maps the domain {@link AgentRun}, its task list and its scratchpad listing to one API response.
     */
    public static AgentRunResponse from(
            AgentRun run, List<AgentTask> tasks, List<AgentArtifact> files) {
        return new AgentRunResponse(
                run.id(),
                run.sessionId(),
                run.prompt(),
                run.status(),
                run.finalAnswer(),
                run.steps().stream().map(AgentStepResponse::from).toList(),
                run.createdAt(),
                run.completedAt(),
                run.parentRunId(),
                run.rootRunId(),
                run.pendingAction() == null ? null : PlannedActionResponse.from(run.pendingAction()),
                tasks.stream().map(AgentTaskResponse::from).toList(),
                files.stream().map(AgentArtifactSummaryResponse::from).toList());
    }
}
