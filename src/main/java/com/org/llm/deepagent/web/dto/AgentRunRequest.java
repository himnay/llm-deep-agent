package com.org.llm.orchestrator.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRunRequest(
    @NotBlank(message = "'prompt' is required and must not be blank")
        @Size(
            max = 10_000,
            message = "'prompt' exceeds the maximum allowed length of 10,000 characters")
        String prompt,
    String sessionId) {}
