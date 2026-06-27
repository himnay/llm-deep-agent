package com.org.llm.deepagent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors llm-gateway-core's {@code LlmResponse} — only the fields this client reads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayChatResponse(
        String content,
        String model,
        String provider,
        String error,
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens,
        @JsonProperty("latency_ms") Long latencyMs,
        @JsonProperty("request_id") String requestId) {
}
