package com.org.llm.deepagent.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/** Consistent error body shape, matching the rest of the platform's {@code ApiError} convention. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    Instant timestamp, int status, String error, String message, Map<String, String> fieldErrors) {

  public static ApiError of(int status, String error, String message) {
    return new ApiError(Instant.now(), status, error, message, null);
  }

  public static ApiError of(
      int status, String error, String message, Map<String, String> fieldErrors) {
    return new ApiError(Instant.now(), status, error, message, fieldErrors);
  }
}
