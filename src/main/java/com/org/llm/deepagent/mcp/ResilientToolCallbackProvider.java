package com.org.llm.orchestrator.mcp;

import com.org.llm.orchestrator.exception.McpToolCallException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps every MCP tool callback with a per-server Resilience4j retry + circuit breaker
 * (resilience4j instance name = the connection name configured in {@code
 * spring.ai.mcp.client.streamable-http.connections.*}, e.g. {@code deployment}, {@code github}).
 * Retry fires first (transient errors get retried before the circuit sees the failure); only
 * persistent failures propagate to the circuit breaker. When a server's circuit is OPEN the tool
 * returns a structured error message instead of making a doomed network call, so the planner LLM
 * can explain the outage gracefully rather than hang.
 */
@Slf4j
public class ResilientToolCallbackProvider implements ToolCallbackProvider {

  private final ToolCallbackProvider delegate;
  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final RetryRegistry retryRegistry;
  private final int toolTimeoutSeconds;
  private final Map<String, String> toolToServer;

  public ResilientToolCallbackProvider(
      ToolCallbackProvider delegate,
      CircuitBreakerRegistry circuitBreakerRegistry,
      RetryRegistry retryRegistry,
      int toolTimeoutSeconds,
      Map<String, String> toolToServer) {
    this.delegate = delegate;
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.retryRegistry = retryRegistry;
    this.toolTimeoutSeconds = toolTimeoutSeconds;
    this.toolToServer = toolToServer;
  }

  @Override
  public ToolCallback[] getToolCallbacks() {
    return Arrays.stream(delegate.getToolCallbacks()).map(this::wrap).toArray(ToolCallback[]::new);
  }

  private ToolCallback wrap(ToolCallback callback) {
    String toolName = callback.getToolDefinition().name();
    String serverName = toolToServer.getOrDefault(toolName, "mcp-unknown");
    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(serverName);
    Retry retry = retryRegistry.retry(serverName);
    return new ResilientToolCallback(callback, cb, retry, serverName, toolTimeoutSeconds);
  }

  private static final class ResilientToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final String serverName;
    private final int toolTimeoutSeconds;

    ResilientToolCallback(
        ToolCallback delegate,
        CircuitBreaker cb,
        Retry retry,
        String serverName,
        int toolTimeoutSeconds) {
      this.delegate = delegate;
      this.circuitBreaker = cb;
      this.retry = retry;
      this.serverName = serverName;
      this.toolTimeoutSeconds = toolTimeoutSeconds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
      return execute(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
      return execute(() -> delegate.call(toolInput, toolContext));
    }

    private String execute(Callable<String> action) {
      // Retry wraps the circuit breaker — transient failures are retried before the circuit
      // breaker counts them as failures.
      Callable<String> withCb = CircuitBreaker.decorateCallable(circuitBreaker, action);
      Callable<String> withRetryAndCb = Retry.decorateCallable(retry, withCb);
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      Future<String> future = executor.submit(withRetryAndCb);
      try {
        return future.get(toolTimeoutSeconds, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        log.warn("MCP_TOOL | timed out after {}s | server={}", toolTimeoutSeconds, serverName);
        return "Tool call timed out after " + toolTimeoutSeconds + "s";
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof CallNotPermittedException) {
          log.warn("MCP_TOOL | circuit OPEN | server={}", serverName);
          return "{\"error\":\""
              + serverName
              + " is temporarily unavailable (circuit open). Please try again later.\"}";
        }
        log.error("MCP_TOOL | call failed | server={} | {}", serverName, cause.getMessage());
        throw new McpToolCallException("MCP tool call failed: " + cause.getMessage(), cause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return "Tool call interrupted";
      } finally {
        executor.shutdown();
      }
    }
  }
}
