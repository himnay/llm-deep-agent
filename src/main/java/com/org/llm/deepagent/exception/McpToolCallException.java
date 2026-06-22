package com.org.llm.orchestrator.exception;

/**
 * Thrown when an MCP tool invocation fails after retries/circuit-breaker handling have been
 * exhausted.
 */
public class McpToolCallException extends RuntimeException {

  public McpToolCallException(String message) {
    super(message);
  }

  public McpToolCallException(String message, Throwable cause) {
    super(message, cause);
  }
}
