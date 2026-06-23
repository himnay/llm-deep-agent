package com.org.llm.deepagent.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentArtifact;
import com.org.llm.deepagent.agent.AgentProperties;
import com.org.llm.deepagent.agent.PlannedAction;
import com.org.llm.deepagent.persistence.AgentArtifactRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dispatches {@link AgentAction#FILE_WRITE} and {@link AgentAction#FILE_READ} steps — a small
 * virtual filesystem the planner can use to stash intermediate findings across steps (and, since
 * it's scoped by {@code rootRunId}, across a parent run and its sub-agents too).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScratchpadRoutingStrategy implements RoutingStrategy {

  private final AgentArtifactRepository agentArtifactRepository;
  private final AgentProperties agentProperties;
  private final ObjectMapper objectMapper;

  @Override
  public boolean supports(AgentAction action) {
    return action == AgentAction.FILE_WRITE || action == AgentAction.FILE_READ;
  }

  @Override
  public StepResult execute(AgentContext context, PlannedAction plannedAction) {
    if (plannedAction.action() == AgentAction.FILE_WRITE) {
      return write(context, plannedAction);
    }
    return read(context, plannedAction);
  }

  private StepResult write(AgentContext context, PlannedAction plannedAction) {
    FileOperationInput parsed = parseInput(plannedAction.input());
    if (parsed == null || parsed.path() == null || parsed.path().isBlank()) {
      return StepResult.error(
          "FILE_WRITE input must be a JSON object: {\"path\":...,\"content\":...}");
    }
    String content = parsed.content() == null ? "" : parsed.content();
    if (content.length() > agentProperties.getMaxScratchpadFileChars()) {
      return StepResult.error(
          "File content is "
              + content.length()
              + " characters, exceeding the limit of "
              + agentProperties.getMaxScratchpadFileChars()
              + ".");
    }

    boolean alreadyExists =
        agentArtifactRepository
            .findByRootRunIdAndPath(context.rootRunId(), parsed.path())
            .isPresent();
    if (!alreadyExists
        && agentArtifactRepository.countByRootRunId(context.rootRunId())
            >= agentProperties.getMaxScratchpadFiles()) {
      return StepResult.error(
          "Scratchpad already has "
              + agentProperties.getMaxScratchpadFiles()
              + " files; delete/reuse an existing path instead of creating a new one.");
    }

    agentArtifactRepository.upsert(context.rootRunId(), parsed.path(), content);
    return StepResult.ok("Wrote " + content.length() + " characters to " + parsed.path());
  }

  private StepResult read(AgentContext context, PlannedAction plannedAction) {
    FileOperationInput parsed = parseInput(plannedAction.input());
    if (parsed == null || parsed.path() == null || parsed.path().isBlank()) {
      return StepResult.error("FILE_READ input must be a JSON object: {\"path\":...}");
    }

    Optional<AgentArtifact> artifact =
        agentArtifactRepository.findByRootRunIdAndPath(context.rootRunId(), parsed.path());
    if (artifact.isEmpty()) {
      List<String> existing =
          agentArtifactRepository.listByRootRunId(context.rootRunId()).stream()
              .map(AgentArtifact::path)
              .toList();
      return StepResult.error("No file at '" + parsed.path() + "'. Existing files: " + existing);
    }
    return StepResult.ok(artifact.get().content());
  }

  private FileOperationInput parseInput(String input) {
    try {
      return objectMapper.readValue(input, FileOperationInput.class);
    } catch (Exception e) {
      log.warn("SCRATCHPAD | could not parse file operation JSON | {}", e.getMessage());
      return null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FileOperationInput(String path, String content) {}
}
