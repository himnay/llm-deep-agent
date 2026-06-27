package com.org.llm.deepagent.config;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The background executor that runs agent loops once {@code POST /agent/run} returns — kept as an
 * injectable bean (rather than constructed inline) so tests can substitute a synchronous executor.
 * Wrapped with Micrometer's context-propagation so logs/traces emitted on the background virtual
 * thread still carry the originating request's MDC values (and any other ThreadLocal registered
 * with {@link ContextRegistry}) instead of looking unrelated in the log/trace backend.
 */
@Configuration
public class AgentExecutorConfig {

    public AgentExecutorConfig() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
    }

    /**
     * Virtual-thread-per-task executor — matches the pattern already used for MCP tool calls.
     */
    @Bean
    public Executor agentRunExecutor() {
        ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
        return ContextExecutorService.wrap(virtualThreads);
    }
}
