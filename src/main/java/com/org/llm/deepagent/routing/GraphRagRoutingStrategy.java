package com.org.llm.deepagent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.dto.PlannedAction;
import com.org.llm.deepagent.client.GraphRagClient;
import com.org.llm.deepagent.client.dto.GraphRagResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dispatches {@link AgentAction#GRAPH_RAG_QUERY} to llm-rag-graph.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphRagRoutingStrategy implements RoutingStrategy {

    private final GraphRagClient graphRagClient;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AgentAction action) {
        return action == AgentAction.GRAPH_RAG_QUERY;
    }

    @Override
    public StepResult execute(AgentContext context, PlannedAction plannedAction) {
        GraphRagResponse response = graphRagClient.query(plannedAction.input());
        try {
            return StepResult.ok(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("GRAPH_RAG_ROUTING | failed to serialize response | {}", e.getMessage());
            return StepResult.ok(response.answer());
        }
    }
}
