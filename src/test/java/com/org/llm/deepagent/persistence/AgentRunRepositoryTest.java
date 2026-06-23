package com.org.llm.deepagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentRun;
import com.org.llm.deepagent.agent.AgentRunStatus;
import com.org.llm.deepagent.agent.AgentStep;
import com.org.llm.deepagent.agent.PlannedAction;
import com.org.llm.deepagent.config.JacksonConfig;
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
      JacksonConfig.class,
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

  @Test
  @DisplayName("createRun() for a top-level run self-references root_run_id")
  void createTopLevelRunSelfReferencesRoot() {
    long runId = repository.createRun("session-2", "top level");

    AgentRun run = repository.findById(runId);

    assertThat(run.parentRunId()).isNull();
    assertThat(run.rootRunId()).isEqualTo(runId);
  }

  @Test
  @DisplayName("createRun() for a sub-agent sets parent_run_id and inherits the given root_run_id")
  void createSubAgentRunInheritsRoot() {
    long rootRunId = repository.createRun("session-3", "top level");
    long subRunId = repository.createRun("session-3", "sub task", rootRunId, rootRunId);

    AgentRun subRun = repository.findById(subRunId);

    assertThat(subRun.parentRunId()).isEqualTo(rootRunId);
    assertThat(subRun.rootRunId()).isEqualTo(rootRunId);
  }

  @Test
  @DisplayName(
      "markAwaitingApproval() then claimPendingAction() round-trips status and pending action")
  void markAwaitingApprovalThenClaim() {
    long runId = repository.createRun(null, "deploy it");
    PlannedAction pendingAction =
        new PlannedAction(AgentAction.MCP_TOOL, "deploy", "{}", "needs care");

    repository.markAwaitingApproval(runId, pendingAction);
    AgentRun paused = repository.findById(runId);

    assertThat(paused.status()).isEqualTo(AgentRunStatus.AWAITING_APPROVAL);
    assertThat(paused.pendingAction()).isEqualTo(pendingAction);

    assertThat(repository.claimPendingAction(runId)).isTrue();
    AgentRun resumed = repository.findById(runId);

    assertThat(resumed.status()).isEqualTo(AgentRunStatus.RUNNING);
    assertThat(resumed.pendingAction()).isNull();
  }

  @Test
  @DisplayName("claimPendingAction() only succeeds once — a second claim on the same run fails")
  void claimPendingActionOnlySucceedsOnce() {
    long runId = repository.createRun(null, "deploy it");
    repository.markAwaitingApproval(
        runId, new PlannedAction(AgentAction.MCP_TOOL, "deploy", "{}", "r"));

    assertThat(repository.claimPendingAction(runId)).isTrue();
    assertThat(repository.claimPendingAction(runId)).isFalse();
  }

  @Test
  @DisplayName("updateContextSummary() persists the rolling summary and summarized step count")
  void updateContextSummaryPersists() {
    long runId = repository.createRun(null, "a long task");

    repository.updateContextSummary(runId, "did steps 1-4 already", 4);

    AgentRun run = repository.findById(runId);
    assertThat(run.contextSummary()).isEqualTo("did steps 1-4 already");
    assertThat(run.summarizedStepCount()).isEqualTo(4);
  }

  @Test
  @DisplayName("findRecentBySession() returns prior resolved top-level runs, most recent first")
  void findRecentBySessionReturnsResolvedPriorRuns() {
    long first = repository.createRun("session-4", "first question");
    repository.complete(first, AgentRunStatus.COMPLETED, "first answer");
    long second = repository.createRun("session-4", "second question");
    repository.complete(second, AgentRunStatus.COMPLETED, "second answer");
    long stillRunning = repository.createRun("session-4", "third question");
    long subAgentOfSecond = repository.createRun("session-4", "sub task", second, second);
    repository.complete(subAgentOfSecond, AgentRunStatus.COMPLETED, "sub answer");

    var recent = repository.findRecentBySession("session-4", stillRunning, 5);

    assertThat(recent).extracting(AgentRun::id).containsExactly(second, first);
  }
}
