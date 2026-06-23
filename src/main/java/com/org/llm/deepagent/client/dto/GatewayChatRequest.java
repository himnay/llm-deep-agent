package com.org.llm.deepagent.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors llm-gateway-core's {@code LlmRequest} — only the fields this client needs to send. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GatewayChatRequest(
    String prompt,
    String provider,
    String model,
    @JsonProperty("system_prompt") String systemPrompt,
    @JsonProperty("session_id") String sessionId) {}
