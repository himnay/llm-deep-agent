package com.org.llm.deepagent.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.AgentAction;
import com.org.llm.deepagent.agent.AgentProperties;
import com.org.llm.deepagent.agent.AgentTask;
import com.org.llm.deepagent.agent.AgentTaskStatus;
import com.org.llm.deepagent.agent.PlannedAction;
import com.org.llm.deepagent.persistence.AgentTaskRepository;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Dispatches {@link AgentAction#PLAN_TASKS} steps: the planner sends the full desired task list as
 * a JSON array in {@code input}, which replaces the run tree's task list wholesale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPlanningRoutingStrategy implements RoutingStrategy {

  private final AgentTaskRepository agentTaskRepository;
  private final AgentProperties agentProperties;
  private final ObjectMapper objectMapper;

  @Override
  public boolean supports(AgentAction action) {
    return action == AgentAction.PLAN_TASKS;
  }

  @Override
  public StepResult execute(AgentContext context, PlannedAction plannedAction) {
    List<PlannedTaskInput> parsed;
    try {
      parsed =
          Arrays.asList(objectMapper.readValue(plannedAction.input(), PlannedTaskInput[].class));
    } catch (Exception e) {
      log.warn("TASK_PLANNING | could not parse task list JSON | {}", e.getMessage());
      return StepResult.error(
          "PLAN_TASKS input must be a JSON array of {taskKey, description, status}: "
              + e.getMessage());
    }

    if (parsed.size() > agentProperties.getMaxTasks()) {
      return StepResult.error(
          "Task list has "
              + parsed.size()
              + " entries, exceeding the limit of "
              + agentProperties.getMaxTasks()
              + ". Trim the list and try again.");
    }

    long distinctKeys = parsed.stream().map(PlannedTaskInput::taskKey).distinct().count();
    if (distinctKeys < parsed.size()) {
      return StepResult.error(
          "Task list has duplicate taskKey values: "
              + parsed.stream().map(PlannedTaskInput::taskKey).collect(Collectors.joining(", "))
              + ". Each taskKey must be unique within the list.");
    }

    List<AgentTask> tasks =
        parsed.stream()
            .map(
                t ->
                    new AgentTask(
                        null, context.rootRunId(), t.taskKey(), t.description(), t.status(), null))
            .toList();
    try {
      agentTaskRepository.replaceAll(context.rootRunId(), tasks);
    } catch (DataIntegrityViolationException e) {
      log.warn("TASK_PLANNING | replaceAll rejected by the database | {}", e.getMessage());
      return StepResult.error(
          "Could not save the task list — each taskKey must be unique. Try again with distinct keys.");
    }

    return StepResult.ok("Task list updated (" + tasks.size() + " tasks).");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record PlannedTaskInput(String taskKey, String description, AgentTaskStatus status) {}
}
