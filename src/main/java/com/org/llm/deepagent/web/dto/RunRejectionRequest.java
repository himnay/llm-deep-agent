package com.org.llm.deepagent.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Body for POST /agent/run/{runId}/reject.")
public record RunRejectionRequest(
        @Schema(
                description =
                        "Why the pending action was rejected — fed back to the planner so it can adapt.",
                example = "This deployment tool targets prod; re-check the environment first.")
        String reason) {
}
