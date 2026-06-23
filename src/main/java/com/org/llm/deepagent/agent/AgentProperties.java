package com.org.llm.deepagent.agent;

import jakarta.validation.constraints.Min;
import java.util.EnumSet;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Settings for the plan/act/observe loop (prefix {@code agent}). */
@Validated
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

  /**
   * Hard cap on loop iterations for a top-level run — prevents a confused planner from looping
   * forever.
   */
  @Min(1)
  private int maxIterations = 25;

  /** Hard cap on loop iterations for a run started by DELEGATE_SUBAGENT. */
  @Min(1)
  private int subAgentMaxIterations = 6;

  /** Per-MCP-tool-call timeout, also reused as the resilience4j tool-call timeout. */
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
   * Actions that pause the run with status AWAITING_APPROVAL until a human approves/rejects them.
   */
  private Set<AgentAction> approvalRequiredActions = EnumSet.of(AgentAction.MCP_TOOL);

  /**
   * How many prior runs for the same sessionId are summarized into a new top-level run's first
   * prompt.
   */
  @Min(0)
  private int sessionHistoryLimit = 3;

  /** Max number of tasks a single PLAN_TASKS call may persist for a run tree. */
  @Min(1)
  private int maxTasks = 50;

  /** Max number of distinct scratchpad files a run tree may accumulate. */
  @Min(1)
  private int maxScratchpadFiles = 20;

  /** Max characters allowed in a single scratchpad file's content. */
  @Min(1)
  private int maxScratchpadFileChars = 20_000;
}
