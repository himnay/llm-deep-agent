package com.org.llm.orchestrator.web;

import com.org.llm.orchestrator.exception.AgentRunNotFoundException;
import com.org.llm.orchestrator.exception.McpToolCallException;
import com.org.llm.orchestrator.exception.TokenAcquisitionException;
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

  @ExceptionHandler(AgentRunNotFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(AgentRunNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not found", ex.getMessage()));
  }

  @ExceptionHandler({McpToolCallException.class, TokenAcquisitionException.class})
  public ResponseEntity<ApiError> handleUpstreamFailure(RuntimeException ex) {
    log.error("ORCHESTRATOR | upstream dependency failure | {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(
            ApiError.of(
                HttpStatus.BAD_GATEWAY.value(), "Upstream dependency failed", ex.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleGeneric(Exception ex) {
    log.error("ORCHESTRATOR | unhandled error | {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal error", ex.getMessage()));
  }
}
