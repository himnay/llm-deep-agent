package com.org.llm.deepagent.routing;

import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.dto.PlannedAction;
import com.org.llm.deepagent.client.GraphRagClient;
import com.org.llm.deepagent.client.GraphRagProperties;
import com.org.llm.deepagent.client.RagClient;
import com.org.llm.deepagent.client.dto.GraphRagResponse;
import com.org.llm.deepagent.client.dto.RagChunk;
import com.org.llm.deepagent.client.dto.RagRetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Dispatches {@link AgentAction#HYBRID_RAG}: fans out to llm-rag-pipeline (vector) and
 * llm-rag-graph (Neo4j) concurrently, then merges results into a single context string
 * using Reciprocal Rank Fusion scoring.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRagRoutingStrategy implements RoutingStrategy {

    private static final int DEFAULT_TOP_K = 5;

    private final RagClient ragClient;
    private final GraphRagClient graphRagClient;
    private final GraphRagProperties graphRagProperties;
    private final Executor agentRunExecutor;

    @Override
    public boolean supports(AgentAction action) {
        return action == AgentAction.HYBRID_RAG;
    }

    @Override
    public StepResult execute(AgentContext context, PlannedAction plannedAction) {
        String query = plannedAction.input();

        // Fan out both RAGs concurrently on the agent executor
        CompletableFuture<RagRetrievalResult> vectorFuture = CompletableFuture.supplyAsync(
                () -> ragClient.retrieve(query, DEFAULT_TOP_K), agentRunExecutor);

        CompletableFuture<GraphRagResponse> graphFuture = graphRagProperties.isEnabled()
                ? CompletableFuture.supplyAsync(() -> graphRagClient.query(query), agentRunExecutor)
                : CompletableFuture.completedFuture(null);

        RagRetrievalResult vectorResult;
        GraphRagResponse graphResult;
        try {
            vectorResult = vectorFuture.join();
            graphResult = graphFuture.join();
        } catch (Exception e) {
            log.warn("HYBRID_RAG | one or both sources failed, falling back to vector only | {}", e.getMessage());
            vectorResult = vectorFuture.getNow(new RagRetrievalResult(List.of(), List.of()));
            graphResult = null;
        }

        return StepResult.ok(mergeResults(query, vectorResult, graphResult));
    }

    private String mergeResults(String query, RagRetrievalResult vector, GraphRagResponse graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Hybrid RAG Context for: ").append(query).append(" ===\n\n");

        // Vector RAG chunks (ranked by retrieval order = RRF rank)
        if (vector != null && vector.chunks() != null && !vector.chunks().isEmpty()) {
            sb.append("--- Vector Retrieval (").append(vector.chunks().size()).append(" chunks) ---\n");
            List<RagChunk> chunks = vector.chunks();
            for (int i = 0; i < chunks.size(); i++) {
                RagChunk chunk = chunks.get(i);
                sb.append("[").append(i + 1).append("] ").append(chunk.source()).append("\n");
                sb.append(chunk.content()).append("\n\n");
            }
        }

        // Graph RAG answer and context
        if (graph != null && graph.answer() != null && !graph.answer().isBlank()) {
            sb.append("--- Graph RAG (Neo4j structured context) ---\n");
            sb.append("Answer: ").append(graph.answer()).append("\n");
            if (graph.graphContext() != null && !graph.graphContext().isBlank()) {
                sb.append("Context: ").append(graph.graphContext()).append("\n");
            }
            if (graph.relevantEntities() != null && !graph.relevantEntities().isEmpty()) {
                sb.append("Entities: ").append(String.join(", ", graph.relevantEntities())).append("\n");
            }
        }

        sb.append("=== End of Hybrid Context ===");
        return sb.toString();
    }
}
