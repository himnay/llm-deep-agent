package com.org.llm.deepagent.web;

import com.org.llm.deepagent.exception.AgentArtifactNotFoundException;
import com.org.llm.deepagent.exception.AgentRunNotFoundException;
import com.org.llm.deepagent.exception.InvalidRunStateException;
import com.org.llm.deepagent.exception.McpToolCallException;
import com.org.llm.deepagent.exception.RunAccessDeniedException;
import com.org.llm.deepagent.exception.TokenAcquisitionException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps every exception that escapes a controller to a consistent {@link ApiError} JSON body. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /** Maps a {@code @Valid} request-body failure to 400, with one field error per violation. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fieldErrors.put(f.getField(), f.getDefaultMessage()));
    return ResponseEntity.badRequest()
        .body(
            ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                "One or more fields are invalid",
                fieldErrors));
  }

  /** Maps an attempt to access a run owned by someone else to 403. */
  @ExceptionHandler(RunAccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(RunAccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiError.of(HttpStatus.FORBIDDEN.value(), "Access denied", ex.getMessage()));
  }

  /** Maps a lookup of a non-existent run id or scratchpad file to 404. */
  @ExceptionHandler({AgentRunNotFoundException.class, AgentArtifactNotFoundException.class})
  public ResponseEntity<ApiError> handleNotFound(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not found", ex.getMessage()));
  }

  /**
   * Maps a state-precondition violation (e.g. approving a run that isn't awaiting approval) to 409.
   */
  @ExceptionHandler(InvalidRunStateException.class)
  public ResponseEntity<ApiError> handleInvalidRunState(InvalidRunStateException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiError.of(HttpStatus.CONFLICT.value(), "Invalid run state", ex.getMessage()));
  }

  /** Maps a downstream/upstream dependency failure (MCP tool call, token acquisition) to 502. */
  @ExceptionHandler({McpToolCallException.class, TokenAcquisitionException.class})
  public ResponseEntity<ApiError> handleUpstreamFailure(RuntimeException ex) {
    log.error("ORCHESTRATOR | upstream dependency failure | {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(
            ApiError.of(
                HttpStatus.BAD_GATEWAY.value(), "Upstream dependency failed", ex.getMessage()));
  }

  /** Catch-all fallback for anything not handled above; maps to 500. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleGeneric(Exception ex) {
    log.error("ORCHESTRATOR | unhandled error | {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal error", ex.getMessage()));
  }
}
