package com.org.llm.deepagent.agent;

import com.org.llm.deepagent.agent.dto.AgentAction;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;

/**
 * Settings for the plan/act/observe loop (prefix {@code agent}).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /**
     * Hard cap on loop iterations for a top-level run — prevents a confused planner from looping
     * forever.
     */
    @Min(1)
    private int maxIterations = 25;

    /**
     * Hard cap on loop iterations for a run started by DELEGATE_SUBAGENT.
     */
    @Min(1)
    private int subAgentMaxIterations = 6;

    /**
     * Per-MCP-tool-call timeout, also reused as the resilience4j tool-call timeout.
     */
    @Min(1)
    private int stepTimeoutSeconds = 30;

    /**
     * Once a run has more persisted steps than this, the older ones are folded into a rolling
     * summary.
     */
    @Min(1)
    private int compactionTriggerSteps = 8;

    /**
     * How many of the most recent steps stay verbatim in the transcript once compaction has
     * triggered.
     */
    @Min(1)
    private int compactionKeepRecentSteps = 4;

    /**
     * Non-MCP actions that pause the run with status AWAITING_APPROVAL until a human approves/rejects
     * them. MCP_TOOL is deliberately excluded here — see {@link #approvalRequiredMcpTools} for
     * per-tool (risk-aware) gating instead of gating every tool call uniformly.
     */
    private Set<AgentAction> approvalRequiredActions = Set.of();

    /**
     * Which MCP tools require approval before a call is dispatched, matched against the planner's
     * chosen {@code toolName}. Each entry is either an exact tool name or a {@code prefix*} pattern
     * (e.g. {@code "deploy*"}); the default {@code "*"} gates every tool, matching this project's
     * original (coarser) behavior — narrow it to just the mutating tools once you know your
     * catalogue.
     */
    private List<String> approvalRequiredMcpTools = List.of("*");

    /**
     * Hard cap on cumulative prompt+completion tokens a run may spend before it's stopped as
     * INCOMPLETE.
     */
    @Min(1)
    private int maxTotalTokens = 200_000;

    /**
     * How long a terminal (non-running) top-level run is kept before {@code AgentRunRetentionJob}
     * prunes it.
     */
    @Min(1)
    private int retentionDays = 30;

    /**
     * How many prior runs for the same sessionId are summarized into a new top-level run's first
     * prompt.
     */
    @Min(0)
    private int sessionHistoryLimit = 3;

    /**
     * Max number of tasks a single PLAN_TASKS call may persist for a run tree.
     */
    @Min(1)
    private int maxTasks = 50;

    /**
     * Max number of distinct scratchpad files a run tree may accumulate.
     */
    @Min(1)
    private int maxScratchpadFiles = 20;

    /**
     * Max characters allowed in a single scratchpad file's content.
     */
    @Min(1)
    private int maxScratchpadFileChars = 20_000;

    private static boolean matchesToolPattern(String pattern, String toolName) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return toolName.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(toolName);
    }

    /**
     * Whether a planned action should pause for human approval: non-MCP actions are gated by {@link
     * #approvalRequiredActions}; MCP_TOOL calls are instead gated per-tool by {@link
     * #approvalRequiredMcpTools} (exact match or a {@code prefix*} pattern), independent of whatever
     * {@link #approvalRequiredActions} contains.
     */
    public boolean isApprovalRequired(AgentAction action, String toolName) {
        if (action != AgentAction.MCP_TOOL) {
            return approvalRequiredActions.contains(action);
        }
        String name = toolName == null ? "" : toolName;
        return approvalRequiredMcpTools.stream().anyMatch(pattern -> matchesToolPattern(pattern, name));
    }
}
