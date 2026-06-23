package com.org.llm.deepagent.web.dto;

import com.org.llm.deepagent.agent.AgentArtifact;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "The full content of one scratchpad file.")
public record AgentArtifactResponse(
    @Schema(description = "The file's path within the run tree's scratchpad.") String path,
    @Schema(description = "The file's full content.") String content,
    @Schema(description = "When this file was last written.") Instant updatedAt) {

  /** Maps the domain {@link AgentArtifact} to its API representation. */
  public static AgentArtifactResponse from(AgentArtifact artifact) {
    return new AgentArtifactResponse(artifact.path(), artifact.content(), artifact.updatedAt());
  }
}
