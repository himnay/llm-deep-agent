package com.org.llm.deepagent.exception;

/**
 * Thrown when {@code approve()}/{@code reject()} (or another state-dependent operation) is called
 * on an {@code AgentRun} that isn't currently in the state that operation requires.
 */
public class InvalidRunStateException extends RuntimeException {

  public InvalidRunStateException(String message) {
    super(message);
  }
}
