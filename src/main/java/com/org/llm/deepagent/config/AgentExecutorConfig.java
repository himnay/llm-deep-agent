package com.org.llm.deepagent.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The background executor that runs agent loops once {@code POST /agent/run} returns — kept as an
 * injectable bean (rather than constructed inline) so tests can substitute a synchronous executor.
 */
@Configuration
public class AgentExecutorConfig {

  /** Virtual-thread-per-task executor — matches the pattern already used for MCP tool calls. */
  @Bean
  public Executor agentRunExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
