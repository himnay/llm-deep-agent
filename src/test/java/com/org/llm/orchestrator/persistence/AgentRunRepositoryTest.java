package com.org.llm.orchestrator.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.org.llm.orchestrator.agent.AgentAction;
import com.org.llm.orchestrator.agent.AgentRun;
import com.org.llm.orchestrator.agent.AgentRunStatus;
import com.org.llm.orchestrator.agent.AgentStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = {
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      FlywayAutoConfiguration.class,
      AgentRunRepository.class
    })
class AgentRunRepositoryTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

  @org.springframework.beans.factory.annotation.Autowired private AgentRunRepository repository;

  @Test
  @DisplayName("createRun() then findById() round-trips a run with no steps yet")
  void createAndFindRunWithNoSteps() {
    long runId = repository.createRun("session-1", "what is 2+2?");

    AgentRun run = repository.findById(runId);

    assertThat(run).isNotNull();
    assertThat(run.sessionId()).isEqualTo("session-1");
    assertThat(run.prompt()).isEqualTo("what is 2+2?");
    assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
    assertThat(run.steps()).isEmpty();
  }

  @Test
  @DisplayName("saveStep() persists steps in stepIndex order and complete() finalizes the run")
  void saveStepsThenComplete() {
    long runId = repository.createRun(null, "deploy service x");

    repository.saveStep(
        new AgentStep(
            null, runId, 0, AgentAction.MCP_TOOL, "getDeployments", "{}", "obs-0", "r0", null));
    repository.saveStep(
        new AgentStep(
            null, runId, 1, AgentAction.GATEWAY_LLM, null, "summarize", "obs-1", "r1", null));
    repository.complete(runId, AgentRunStatus.COMPLETED, "deployment is healthy");

    AgentRun run = repository.findById(runId);

    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.finalAnswer()).isEqualTo("deployment is healthy");
    assertThat(run.completedAt()).isNotNull();
    assertThat(run.steps()).hasSize(2);
    assertThat(run.steps().get(0).stepIndex()).isZero();
    assertThat(run.steps().get(1).stepIndex()).isEqualTo(1);
  }

  @Test
  @DisplayName("findById() returns null (not an exception) for an id that doesn't exist")
  void findByIdReturnsNullWhenMissing() {
    assertThat(repository.findById(999_999L)).isNull();
  }
}
