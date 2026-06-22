package com.org.llm.orchestrator.exception;

/**
 * Thrown when a client-credentials token request to the identity provider (e.g. Keycloak) fails or
 * returns a response with no usable access token.
 */
public class TokenAcquisitionException extends RuntimeException {

  public TokenAcquisitionException(String message) {
    super(message);
  }

  public TokenAcquisitionException(String message, Throwable cause) {
    super(message, cause);
  }
}
