package com.org.llm.deepagent.web.dto;

import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.dto.PlannedAction;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An action the planner chose, shown when a run is awaiting human approval.")
public record PlannedActionResponse(
        @Schema(description = "Which kind of step this is.") AgentAction action,
        @Schema(description = "The MCP tool name, only set when action is MCP_TOOL.") String toolName,
        @Schema(description = "The query/prompt/tool-arguments for this step.") String input,
        @Schema(description = "The planner's one-line rationale for choosing this action.")
        String reasoning) {

    /**
     * Maps the domain {@link PlannedAction} to its API representation.
     */
    public static PlannedActionResponse from(PlannedAction plannedAction) {
        return new PlannedActionResponse(
                plannedAction.action(),
                plannedAction.toolName(),
                plannedAction.input(),
                plannedAction.reasoning());
    }
}
