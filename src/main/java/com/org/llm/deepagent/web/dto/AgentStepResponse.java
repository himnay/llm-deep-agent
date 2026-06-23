package com.org.llm.deepagent.web.dto;

import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentStep;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One plan/act/observe iteration of an agent run.")
public record AgentStepResponse(
    @Schema(description = "Zero-based position of this step within its run.") int stepIndex,
    @Schema(description = "Which kind of step this was.") AgentAction action,
    @Schema(description = "The MCP tool name, only set when action is MCP_TOOL.") String toolName,
    @Schema(description = "The query/prompt/tool-arguments given for this step.") String input,
    @Schema(description = "What this step produced, fed into the next planning turn.")
        String observation,
    @Schema(description = "The planner's one-line rationale for choosing this action.")
        String reasoning) {

  /** Maps the domain {@link AgentStep} to its API representation. */
  public static AgentStepResponse from(AgentStep step) {
    return new AgentStepResponse(
        step.stepIndex(),
        step.action(),
        step.toolName(),
        step.input(),
        step.observation(),
        step.reasoning());
  }
}
