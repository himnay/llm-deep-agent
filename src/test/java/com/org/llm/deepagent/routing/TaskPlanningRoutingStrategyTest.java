package com.org.llm.deepagent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.AgentProperties;
import com.org.llm.deepagent.agent.dto.AgentTask;
import com.org.llm.deepagent.agent.dto.PlannedAction;
import com.org.llm.deepagent.persistence.AgentTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskPlanningRoutingStrategyTest {

    private final AgentTaskRepository agentTaskRepository = mock(AgentTaskRepository.class);
    private final AgentProperties agentProperties = new AgentProperties();
    private final TaskPlanningRoutingStrategy strategy =
            new TaskPlanningRoutingStrategy(agentTaskRepository, agentProperties, new ObjectMapper());

    @Test
    @DisplayName("supports() only matches PLAN_TASKS")
    void supportsOnlyPlanTasks() {
        assertThat(strategy.supports(AgentAction.PLAN_TASKS)).isTrue();
        assertThat(strategy.supports(AgentAction.FILE_WRITE)).isFalse();
    }

    @Test
    @DisplayName("execute() replaces the task list for the run's rootRunId with the parsed tasks")
    void executeReplacesTaskList() {
        String input =
                "[{\"taskKey\":\"t1\",\"description\":\"check status\",\"status\":\"IN_PROGRESS\"},"
                        + "{\"taskKey\":\"t2\",\"description\":\"summarize\",\"status\":\"PENDING\"}]";

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 9L, "s1", false),
                        new PlannedAction(AgentAction.PLAN_TASKS, null, input, null));

        assertThat(result.success()).isTrue();
        assertThat(result.observation()).contains("2 tasks");

        ArgumentCaptor<List<AgentTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentTaskRepository).replaceAll(eq(9L), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).rootRunId()).isEqualTo(9L);
        assertThat(captor.getValue().get(0).taskKey()).isEqualTo("t1");
    }

    @Test
    @DisplayName("execute() rejects malformed JSON instead of throwing")
    void executeRejectsMalformedJson() {
        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 9L, null, false),
                        new PlannedAction(AgentAction.PLAN_TASKS, null, "not json", null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("JSON array");
        verify(agentTaskRepository, never())
                .replaceAll(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("execute() rejects a task list larger than agent.max-tasks")
    void executeRejectsTooManyTasks() {
        agentProperties.setMaxTasks(1);
        String input =
                "[{\"taskKey\":\"t1\",\"description\":\"a\",\"status\":\"PENDING\"},"
                        + "{\"taskKey\":\"t2\",\"description\":\"b\",\"status\":\"PENDING\"}]";

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 9L, null, false),
                        new PlannedAction(AgentAction.PLAN_TASKS, null, input, null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("exceeding the limit");
        verify(agentTaskRepository, never())
                .replaceAll(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName(
            "execute() rejects a task list with duplicate taskKeys instead of letting the DB constraint crash the run")
    void executeRejectsDuplicateTaskKeys() {
        String input =
                "[{\"taskKey\":\"t1\",\"description\":\"a\",\"status\":\"PENDING\"},"
                        + "{\"taskKey\":\"t1\",\"description\":\"b\",\"status\":\"PENDING\"}]";

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 9L, null, false),
                        new PlannedAction(AgentAction.PLAN_TASKS, null, input, null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("duplicate taskKey");
        verify(agentTaskRepository, never())
                .replaceAll(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName(
            "execute() surfaces a DB constraint violation as a recoverable error instead of throwing")
    void executeSurfacesDataIntegrityViolationGracefully() {
        String input = "[{\"taskKey\":\"t1\",\"description\":\"a\",\"status\":\"PENDING\"}]";
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("boom"))
                .when(agentTaskRepository)
                .replaceAll(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.any());

        StepResult result =
                strategy.execute(
                        new AgentContext(1L, 9L, null, false),
                        new PlannedAction(AgentAction.PLAN_TASKS, null, input, null));

        assertThat(result.success()).isFalse();
        assertThat(result.observation()).contains("unique");
    }
}
