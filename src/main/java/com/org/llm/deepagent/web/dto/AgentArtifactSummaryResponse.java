package com.org.llm.deepagent.web.dto;

import com.org.llm.deepagent.agent.AgentArtifact;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(
    description =
        "One scratchpad file's identity (no content — fetch GET /agent/run/{runId}/files/{path} for that).")
public record AgentArtifactSummaryResponse(
    @Schema(
            description = "The file's path within the run tree's scratchpad.",
            example = "findings.md")
        String path,
    @Schema(description = "When this file was last written.") Instant updatedAt) {

  /** Maps the domain {@link AgentArtifact} to its content-free API representation. */
  public static AgentArtifactSummaryResponse from(AgentArtifact artifact) {
    return new AgentArtifactSummaryResponse(artifact.path(), artifact.updatedAt());
  }
}
