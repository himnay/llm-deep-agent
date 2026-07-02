package com.org.llm.deepagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.dto.AgentRun;
import com.org.llm.deepagent.client.GatewayClient;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Long-term memory across agent runs.
 *
 * <p><b>Write path</b> ({@link #remember}): when a top-level run completes, one LLM call distills
 * the run's question + final answer into at most {@code max-facts-per-run} standalone facts; each
 * fact is embedded via llm-gateway {@code /embed} and stored in {@code agent_memory}.
 *
 * <p><b>Read path</b> ({@link #recall}): a new run's objective is embedded and compared (cosine)
 * against the newest {@code candidate-limit} stored facts; the top {@code recall-top-k} above
 * {@code min-similarity} are returned as a prompt block for the planner.
 *
 * <p>Both paths are strictly best-effort: any failure logs a warning and degrades to "no memory",
 * never to a failed run.
 */
@Slf4j
@Service
public class LongTermMemoryService {

    private static final String DISTILL_SYSTEM_PROMPT =
            """
            You extract durable facts from a completed AI-agent run.
            Return ONLY a JSON array of strings. Each string is one standalone fact worth \
            remembering for future, unrelated conversations (user preferences, stable domain facts, \
            decisions made, useful identifiers). Do NOT include transient details, step-by-step \
            narration, or anything already obvious. Return [] when nothing is worth keeping.""";

    private final GatewayClient gatewayClient;
    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.memory.max-facts-per-run:5}")
    private int maxFactsPerRun;

    @Value("${app.memory.recall-top-k:5}")
    private int recallTopK;

    @Value("${app.memory.min-similarity:0.75}")
    private double minSimilarity;

    @Value("${app.memory.candidate-limit:500}")
    private int candidateLimit;

    public LongTermMemoryService(
            GatewayClient gatewayClient, MemoryRepository memoryRepository, ObjectMapper objectMapper) {
        this.gatewayClient = gatewayClient;
        this.memoryRepository = memoryRepository;
        this.objectMapper = objectMapper;
    }

    /** Distills and stores facts from a completed run. Never throws. */
    public void remember(AgentRun run) {
        try {
            if (run.finalAnswer() == null || run.finalAnswer().isBlank()) {
                return;
            }
            String userPrompt =
                    "Question:\n" + run.prompt() + "\n\nFinal answer:\n" + run.finalAnswer();
            GatewayChatResponse response = gatewayClient.query(userPrompt, DISTILL_SYSTEM_PROMPT);
            if (response.error() != null || response.content() == null) {
                log.warn("MEMORY | distillation failed | run={} | {}", run.id(), response.error());
                return;
            }
            List<String> facts = parseFacts(response.content());
            int stored = 0;
            for (String fact : facts) {
                if (fact == null || fact.isBlank()) continue;
                if (stored >= maxFactsPerRun) break;
                float[] embedding = gatewayClient.embed(fact);
                memoryRepository.save(run.id(), run.sessionId(), run.createdBy(), fact.strip(), embedding);
                stored++;
            }
            log.info("MEMORY | stored {} fact(s) from run={}", stored, run.id());
        } catch (Exception e) {
            log.warn("MEMORY | remember failed (ignored) | run={} | {}", run.id(), e.getMessage());
        }
    }

    /**
     * Returns a prompt block of relevant memories for the given objective, or an empty string when
     * nothing relevant (or memory is unavailable).
     */
    public String recall(String objective) {
        try {
            float[] queryVec = gatewayClient.embed(objective);
            if (queryVec == null) {
                return "";
            }
            record Scored(MemoryEntry entry, double score) {}
            List<Scored> scored = new ArrayList<>();
            for (MemoryEntry entry : memoryRepository.findRecentWithEmbedding(candidateLimit)) {
                double score = cosine(queryVec, entry.embedding());
                if (score >= minSimilarity) {
                    scored.add(new Scored(entry, score));
                }
            }
            if (scored.isEmpty()) {
                return "";
            }
            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
            StringBuilder block =
                    new StringBuilder("Relevant facts remembered from previous runs:\n");
            scored.stream()
                    .limit(recallTopK)
                    .forEach(s -> block.append("- ").append(s.entry().content()).append('\n'));
            return block.append('\n').toString();
        } catch (Exception e) {
            log.warn("MEMORY | recall failed (ignored) | {}", e.getMessage());
            return "";
        }
    }

    private List<String> parseFacts(String content) {
        try {
            String trimmed = content.trim();
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return List.of();
            }
            return objectMapper.readValue(
                    trimmed.substring(start, end + 1), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("MEMORY | could not parse distilled facts | {}", e.getMessage());
            return List.of();
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return -1;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return -1;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
