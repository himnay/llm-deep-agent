package com.org.llm.orchestrator.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the plan/act/observe loop (prefix {@code agent}). */
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

  /** Hard cap on loop iterations — prevents a confused planner from looping forever. */
  private int maxIterations = 6;

  /** Per-MCP-tool-call timeout, also reused as the resilience4j tool-call timeout. */
  private int stepTimeoutSeconds = 30;
}
