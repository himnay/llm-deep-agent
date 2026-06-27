package com.org.llm.deepagent.routing;

import com.org.llm.deepagent.agent.dto.PlannedAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GoF <b>Chain of Responsibility</b> over the registered {@link RoutingStrategy} beans — Spring
 * injects every implementation on the classpath; the first one whose {@code supports()} matches the
 * planner's chosen action handles the step.
 */
@Component
@RequiredArgsConstructor
public class RoutingStrategyChain {

    private final List<RoutingStrategy> strategies;

    public StepResult dispatch(AgentContext context, PlannedAction plannedAction) {
        return strategies.stream()
                .filter(s -> s.supports(plannedAction.action()))
                .findFirst()
                .map(s -> s.execute(context, plannedAction))
                .orElseGet(
                        () ->
                                StepResult.error(
                                        "No routing strategy registered for action: " + plannedAction.action()));
    }
}
