package com.org.llm.deepagent.persistence;

import com.org.llm.deepagent.agent.dto.AgentArtifact;
import com.org.llm.deepagent.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = {
                DataSourceAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                JacksonConfig.class,
                AgentRunRepository.class,
                AgentArtifactRepository.class
        })
class AgentArtifactRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private AgentArtifactRepository agentArtifactRepository;

    @Test
    @DisplayName("upsert() creates a new file, then findByRootRunIdAndPath() returns it")
    void upsertCreatesThenFinds() {
        long rootRunId = agentRunRepository.createRun("s1", "take notes");

        agentArtifactRepository.upsert(rootRunId, "notes.md", "first draft");

        AgentArtifact artifact =
                agentArtifactRepository.findByRootRunIdAndPath(rootRunId, "notes.md").orElseThrow();
        assertThat(artifact.content()).isEqualTo("first draft");
    }

    @Test
    @DisplayName(
            "upsert() on an existing path overwrites its content rather than duplicating the row")
    void upsertOverwritesExistingFile() {
        long rootRunId = agentRunRepository.createRun("s1", "take notes");
        agentArtifactRepository.upsert(rootRunId, "notes.md", "first draft");

        agentArtifactRepository.upsert(rootRunId, "notes.md", "second draft");

        AgentArtifact artifact =
                agentArtifactRepository.findByRootRunIdAndPath(rootRunId, "notes.md").orElseThrow();
        assertThat(artifact.content()).isEqualTo("second draft");
        assertThat(agentArtifactRepository.countByRootRunId(rootRunId)).isEqualTo(1);
    }

    @Test
    @DisplayName("findByRootRunIdAndPath() is empty for a path that was never written")
    void findReturnsEmptyWhenMissing() {
        long rootRunId = agentRunRepository.createRun("s1", "take notes");

        assertThat(agentArtifactRepository.findByRootRunIdAndPath(rootRunId, "missing.md")).isEmpty();
    }

    @Test
    @DisplayName("listByRootRunId() lists every file for a run tree, ordered by path")
    void listByRootRunIdListsAllFiles() {
        long rootRunId = agentRunRepository.createRun("s1", "take notes");
        agentArtifactRepository.upsert(rootRunId, "b.md", "b");
        agentArtifactRepository.upsert(rootRunId, "a.md", "a");

        List<AgentArtifact> files = agentArtifactRepository.listByRootRunId(rootRunId);

        assertThat(files).extracting(AgentArtifact::path).containsExactly("a.md", "b.md");
    }

    @Test
    @DisplayName("countByRootRunId() reflects the number of distinct files for a run tree")
    void countByRootRunIdCountsDistinctFiles() {
        long rootRunId = agentRunRepository.createRun("s1", "take notes");

        assertThat(agentArtifactRepository.countByRootRunId(rootRunId)).isZero();

        agentArtifactRepository.upsert(rootRunId, "a.md", "a");
        agentArtifactRepository.upsert(rootRunId, "b.md", "b");

        assertThat(agentArtifactRepository.countByRootRunId(rootRunId)).isEqualTo(2);
    }
}
