package com.org.llm.deepagent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentArtifact;
import com.org.llm.deepagent.agent.AgentProperties;
import com.org.llm.deepagent.agent.PlannedAction;
import com.org.llm.deepagent.persistence.AgentArtifactRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScratchpadRoutingStrategyTest {

  private final AgentArtifactRepository agentArtifactRepository =
      mock(AgentArtifactRepository.class);
  private final AgentProperties agentProperties = new AgentProperties();
  private final ScratchpadRoutingStrategy strategy =
      new ScratchpadRoutingStrategy(agentArtifactRepository, agentProperties, new ObjectMapper());

  @Test
  @DisplayName("supports() matches both FILE_WRITE and FILE_READ")
  void supportsBothFileActions() {
    assertThat(strategy.supports(AgentAction.FILE_WRITE)).isTrue();
    assertThat(strategy.supports(AgentAction.FILE_READ)).isTrue();
    assertThat(strategy.supports(AgentAction.GATEWAY_LLM)).isFalse();
  }

  @Test
  @DisplayName("FILE_WRITE upserts the file and reports how many characters were written")
  void writeUpsertsFile() {
    when(agentArtifactRepository.findByRootRunIdAndPath(9L, "notes.md"))
        .thenReturn(Optional.empty());
    when(agentArtifactRepository.countByRootRunId(9L)).thenReturn(0);

    StepResult result =
        strategy.execute(
            new AgentContext(1L, 9L, null, false),
            new PlannedAction(
                AgentAction.FILE_WRITE,
                null,
                "{\"path\":\"notes.md\",\"content\":\"hello\"}",
                null));

    assertThat(result.success()).isTrue();
    assertThat(result.observation()).contains("5 characters").contains("notes.md");
    verify(agentArtifactRepository).upsert(9L, "notes.md", "hello");
  }

  @Test
  @DisplayName("FILE_WRITE rejects a new file once the scratchpad is at its file-count cap")
  void writeRejectsWhenScratchpadFull() {
    agentProperties.setMaxScratchpadFiles(1);
    when(agentArtifactRepository.findByRootRunIdAndPath(9L, "new.md")).thenReturn(Optional.empty());
    when(agentArtifactRepository.countByRootRunId(9L)).thenReturn(1);

    StepResult result =
        strategy.execute(
            new AgentContext(1L, 9L, null, false),
            new PlannedAction(
                AgentAction.FILE_WRITE, null, "{\"path\":\"new.md\",\"content\":\"x\"}", null));

    assertThat(result.success()).isFalse();
    assertThat(result.observation()).contains("Scratchpad already has");
  }

  @Test
  @DisplayName("FILE_WRITE rejects content longer than agent.max-scratchpad-file-chars")
  void writeRejectsOversizedContent() {
    agentProperties.setMaxScratchpadFileChars(3);

    StepResult result =
        strategy.execute(
            new AgentContext(1L, 9L, null, false),
            new PlannedAction(
                AgentAction.FILE_WRITE,
                null,
                "{\"path\":\"notes.md\",\"content\":\"toolong\"}",
                null));

    assertThat(result.success()).isFalse();
    assertThat(result.observation()).contains("exceeding the limit");
  }

  @Test
  @DisplayName("FILE_READ returns the file's content when it exists")
  void readReturnsContent() {
    when(agentArtifactRepository.findByRootRunIdAndPath(9L, "notes.md"))
        .thenReturn(
            Optional.of(new AgentArtifact(1L, 9L, "notes.md", "hello scratchpad", Instant.now())));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, 9L, null, false),
            new PlannedAction(AgentAction.FILE_READ, null, "{\"path\":\"notes.md\"}", null));

    assertThat(result.success()).isTrue();
    assertThat(result.observation()).isEqualTo("hello scratchpad");
  }

  @Test
  @DisplayName("FILE_READ reports the existing files when the requested path doesn't exist")
  void readReportsExistingFilesWhenMissing() {
    when(agentArtifactRepository.findByRootRunIdAndPath(9L, "missing.md"))
        .thenReturn(Optional.empty());
    when(agentArtifactRepository.listByRootRunId(9L))
        .thenReturn(List.of(new AgentArtifact(1L, 9L, "other.md", "", Instant.now())));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, 9L, null, false),
            new PlannedAction(AgentAction.FILE_READ, null, "{\"path\":\"missing.md\"}", null));

    assertThat(result.success()).isFalse();
    assertThat(result.observation()).contains("other.md");
  }
}
