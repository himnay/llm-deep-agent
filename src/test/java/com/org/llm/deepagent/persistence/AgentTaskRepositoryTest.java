package com.org.llm.deepagent.persistence;

import com.org.llm.deepagent.agent.dto.AgentTask;
import com.org.llm.deepagent.agent.dto.AgentTaskStatus;
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
                AgentTaskRepository.class
        })
class AgentTaskRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Test
    @DisplayName("replaceAll() then findByRootRunId() round-trips a task list")
    void replaceAllThenFind() {
        long rootRunId = agentRunRepository.createRun("s1", "do a multi-step thing");

        agentTaskRepository.replaceAll(
                rootRunId,
                List.of(
                        new AgentTask(null, rootRunId, "t1", "check status", AgentTaskStatus.IN_PROGRESS, null),
                        new AgentTask(null, rootRunId, "t2", "summarize", AgentTaskStatus.PENDING, null)));

        List<AgentTask> tasks = agentTaskRepository.findByRootRunId(rootRunId);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).taskKey()).isEqualTo("t1");
        assertThat(tasks.get(0).status()).isEqualTo(AgentTaskStatus.IN_PROGRESS);
        assertThat(tasks.get(1).taskKey()).isEqualTo("t2");
    }

    @Test
    @DisplayName(
            "replaceAll() wholesale-replaces a run's prior task list rather than appending to it")
    void replaceAllReplacesPriorList() {
        long rootRunId = agentRunRepository.createRun("s1", "do a multi-step thing");
        agentTaskRepository.replaceAll(
                rootRunId,
                List.of(
                        new AgentTask(null, rootRunId, "t1", "first version", AgentTaskStatus.PENDING, null)));

        agentTaskRepository.replaceAll(
                rootRunId,
                List.of(
                        new AgentTask(
                                null, rootRunId, "t1", "second version", AgentTaskStatus.COMPLETED, null)));

        List<AgentTask> tasks = agentTaskRepository.findByRootRunId(rootRunId);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).description()).isEqualTo("second version");
        assertThat(tasks.get(0).status()).isEqualTo(AgentTaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("findByRootRunId() returns an empty list for a run tree with no tasks yet")
    void findByRootRunIdReturnsEmptyWhenNone() {
        long rootRunId = agentRunRepository.createRun("s1", "no tasks here");

        assertThat(agentTaskRepository.findByRootRunId(rootRunId)).isEmpty();
    }
}
