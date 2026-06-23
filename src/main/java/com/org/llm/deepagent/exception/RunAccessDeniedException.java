package com.org.llm.deepagent.exception;

/**
 * Thrown when a principal who isn't the run's creator (and isn't ROLE_ADMIN) tries to access it.
 */
public class RunAccessDeniedException extends RuntimeException {

  public RunAccessDeniedException(long runId) {
    super("You do not have access to run " + runId);
  }
}
