package com.org.llm.deepagent.exception;

/**
 * Thrown when {@code GET /agent/run/{runId}/files/{path}} is called for a path that doesn't exist.
 */
public class AgentArtifactNotFoundException extends RuntimeException {

  public AgentArtifactNotFoundException(long rootRunId, String path) {
    super("No scratchpad file at '" + path + "' for run " + rootRunId);
  }
}
