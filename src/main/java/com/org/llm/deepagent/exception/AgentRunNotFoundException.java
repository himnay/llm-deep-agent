package com.org.llm.orchestrator.exception;

/** Thrown when {@code GET /agent/run/{runId}} is called with an id that doesn't exist. */
public class AgentRunNotFoundException extends RuntimeException {

  public AgentRunNotFoundException(long runId) {
    super("No agent run found with id " + runId);
  }
}
