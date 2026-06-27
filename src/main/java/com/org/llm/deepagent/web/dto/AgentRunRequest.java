package com.org.llm.deepagent.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A request to start a new top-level agent run.")
public record AgentRunRequest(
        @Schema(
                description = "The task/question for the agent to work on.",
                example = "Check the deployment status of order-service and summarize it.")
        @NotBlank(message = "'prompt' is required and must not be blank")
        @Size(
                max = 10_000,
                message = "'prompt' exceeds the maximum allowed length of 10,000 characters")
        String prompt,
        @Schema(
                description =
                        "Caller-supplied conversation identifier. Runs sharing a sessionId see each other's"
                                + " prior prompt/answer as conversation history.",
                example = "user-42-chat-1")
        String sessionId) {
}
