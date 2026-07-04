package com.org.llm.deepagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.dto.AgentRun;
import com.org.llm.deepagent.client.GatewayClient;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryServiceTest {

    private GatewayClient gatewayClient;
    private MemoryRepository memoryRepository;
    private LongTermMemoryService service;

    @BeforeEach
    void setUp() {
        gatewayClient = mock(GatewayClient.class);
        memoryRepository = mock(MemoryRepository.class);
        service = new LongTermMemoryService(gatewayClient, memoryRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "maxFactsPerRun", 5);
        ReflectionTestUtils.setField(service, "recallTopK", 2);
        ReflectionTestUtils.setField(service, "minSimilarity", 0.75);
        ReflectionTestUtils.setField(service, "candidateLimit", 500);
    }

    private AgentRun run(String prompt, String finalAnswer) {
        AgentRun run = mock(AgentRun.class, Mockito.RETURNS_DEFAULTS);
        when(run.id()).thenReturn(42L);
        when(run.prompt()).thenReturn(prompt);
        when(run.finalAnswer()).thenReturn(finalAnswer);
        when(run.sessionId()).thenReturn("session-1");
        when(run.createdBy()).thenReturn("alice");
        return run;
    }

    private GatewayChatResponse llmResponse(String content) {
        return new GatewayChatResponse(content, "m", "p", null, null, null, null, 1L, "r");
    }

    @Test
    @DisplayName("remember() stores each distilled fact from the run along with its embedding")
    void rememberStoresEachDistilledFactWithEmbedding() {
        when(gatewayClient.query(anyString(), anyString()))
                .thenReturn(llmResponse("[\"User prefers staging deploys\", \"Team uses Postgres 18\"]"));
        when(gatewayClient.embed(anyString())).thenReturn(new float[] {1f, 0f});

        service.remember(run("deploy the service", "Deployed to staging."));

        verify(memoryRepository).save(eq(42L), eq("session-1"), eq("alice"),
                eq("User prefers staging deploys"), any(float[].class));
        verify(memoryRepository).save(eq(42L), eq("session-1"), eq("alice"),
                eq("Team uses Postgres 18"), any(float[].class));
    }

    @Test
    @DisplayName("remember() only stores up to maxFactsPerRun facts, dropping the rest")
    void rememberCapsFactsPerRun() {
        ReflectionTestUtils.setField(service, "maxFactsPerRun", 1);
        when(gatewayClient.query(anyString(), anyString()))
                .thenReturn(llmResponse("[\"fact one\", \"fact two\"]"));
        when(gatewayClient.embed(anyString())).thenReturn(new float[] {1f});

        service.remember(run("q", "a"));

        verify(memoryRepository).save(any(), any(), any(), eq("fact one"), any());
        verify(memoryRepository, never()).save(any(), any(), any(), eq("fact two"), any());
    }

    @Test
    @DisplayName("remember() skips distillation entirely for runs with no final answer")
    void rememberSkipsRunsWithoutFinalAnswer() {
        service.remember(run("q", null));

        verify(gatewayClient, never()).query(anyString(), anyString());
    }

    @Test
    @DisplayName("remember() tolerates a distillation response that isn't a JSON array")
    void rememberToleratesUnparseableDistillation() {
        when(gatewayClient.query(anyString(), anyString()))
                .thenReturn(llmResponse("no json array here"));

        service.remember(run("q", "a"));

        verify(memoryRepository, never()).save(any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("remember() tolerates a gateway error during distillation without storing anything")
    void rememberToleratesGatewayError() {
        when(gatewayClient.query(anyString(), anyString()))
                .thenReturn(new GatewayChatResponse(null, null, "p", "boom", null, null, null, 1L, "r"));

        service.remember(run("q", "a"));

        verify(memoryRepository, never()).save(any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("recall() returns matches above the similarity threshold, ordered most similar first")
    void recallReturnsTopMatchesAboveThresholdOrderedBySimilarity() {
        when(gatewayClient.embed("what db do we use?")).thenReturn(new float[] {1f, 0f});
        when(memoryRepository.findRecentWithEmbedding(500))
                .thenReturn(List.of(
                        entry(1, "close match", new float[] {0.99f, 0.14f}),
                        entry(2, "exact match", new float[] {1f, 0f}),
                        entry(3, "unrelated", new float[] {0f, 1f})));

        String block = service.recall("what db do we use?");

        assertThat(block).contains("exact match").contains("close match").doesNotContain("unrelated");
        assertThat(block.indexOf("exact match")).isLessThan(block.indexOf("close match"));
    }

    @Test
    @DisplayName("recall() returns an empty result when the query can't be embedded")
    void recallReturnsEmptyWhenEmbeddingUnavailable() {
        when(gatewayClient.embed(anyString())).thenReturn(null);

        assertThat(service.recall("query")).isEmpty();
    }

    @Test
    @DisplayName("recall() returns an empty result when no stored memory is similar enough")
    void recallReturnsEmptyWhenNothingRelevant() {
        when(gatewayClient.embed(anyString())).thenReturn(new float[] {1f, 0f});
        when(memoryRepository.findRecentWithEmbedding(500))
                .thenReturn(List.of(entry(1, "unrelated", new float[] {0f, 1f})));

        assertThat(service.recall("query")).isEmpty();
    }

    @Test
    @DisplayName("recall() swallows repository failures and returns an empty result instead of throwing")
    void recallSwallowsRepositoryFailures() {
        when(gatewayClient.embed(anyString())).thenReturn(new float[] {1f});
        when(memoryRepository.findRecentWithEmbedding(500)).thenThrow(new RuntimeException("db down"));

        assertThat(service.recall("query")).isEmpty();
    }

    private MemoryEntry entry(long id, String content, float[] embedding) {
        return new MemoryEntry(id, null, null, null, content, embedding, null);
    }
}
