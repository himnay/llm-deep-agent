package com.org.llm.deepagent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.dto.PlannedAction;
import com.org.llm.deepagent.client.RagClient;
import com.org.llm.deepagent.client.dto.RagGenerateResponse;
import com.org.llm.deepagent.client.dto.RagRetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dispatches {@link AgentAction#RAG_RETRIEVE} and {@link AgentAction#RAG_GENERATE} steps to
 * llm-rag-pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRoutingStrategy implements RoutingStrategy {

    private static final int DEFAULT_TOP_K = 5;

    private final RagClient ragClient;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AgentAction action) {
        return action == AgentAction.RAG_RETRIEVE || action == AgentAction.RAG_GENERATE;
    }

    @Override
    public StepResult execute(AgentContext context, PlannedAction plannedAction) {
        if (plannedAction.action() == AgentAction.RAG_RETRIEVE) {
            return retrieve(plannedAction);
        }
        return generate(context, plannedAction);
    }

    private StepResult retrieve(PlannedAction plannedAction) {
        RagRetrievalResult result = ragClient.retrieve(plannedAction.input(), DEFAULT_TOP_K);
        try {
            return StepResult.ok(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("RAG_ROUTING | failed to serialize retrieval result | {}", e.getMessage());
            return StepResult.error("Failed to serialize RAG retrieval result: " + e.getMessage());
        }
    }

    private StepResult generate(AgentContext context, PlannedAction plannedAction) {
        RagGenerateResponse response =
                ragClient.generate(plannedAction.input(), DEFAULT_TOP_K, context.sessionId());
        if (response.insufficientContext()) {
            return StepResult.ok(
                    "RAG reports insufficient context. Best-effort answer: " + response.answer());
        }
        return StepResult.ok(response.answer());
    }
}
