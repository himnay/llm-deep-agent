package com.org.llm.deepagent.agent;

import com.org.llm.deepagent.agent.dto.AgentAction;
import com.org.llm.deepagent.agent.dto.AgentRun;
import com.org.llm.deepagent.agent.dto.AgentRunStatus;
import com.org.llm.deepagent.agent.dto.AgentStep;
import com.org.llm.deepagent.client.GatewayClient;
import com.org.llm.deepagent.client.dto.GatewayChatResponse;
import com.org.llm.deepagent.persistence.AgentRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContextCompactorTest {

    private final GatewayClient gatewayClient = mock(GatewayClient.class);
    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final AgentProperties agentProperties = new AgentProperties();
    private final ContextCompactor compactor =
            new ContextCompactor(gatewayClient, agentRunRepository, agentProperties);

    private static AgentStep step(int index, String observation) {
        return new AgentStep(
                null,
                1L,
                index,
                AgentAction.GATEWAY_LLM,
                null,
                "input-" + index,
                observation,
                "r",
                Instant.now());
    }

    private static AgentRun runWith(String contextSummary, int summarizedStepCount) {
        return new AgentRun(
                1L,
                "s1",
                "prompt",
                AgentRunStatus.RUNNING,
                null,
                List.of(),
                Instant.now(),
                null,
                null,
                1L,
                contextSummary,
                summarizedStepCount,
                null,
                "tester",
                0);
    }

    @Test
    @DisplayName(
            "below the trigger threshold, every step is rendered verbatim and the summarizer is never called")
    void belowThresholdRendersVerbatim() {
        agentProperties.setCompactionTriggerSteps(8);
        List<AgentStep> steps = List.of(step(0, "obs-0"), step(1, "obs-1"));

        String transcript = compactor.buildTranscript(runWith(null, 0), steps);

        assertThat(transcript).contains("obs-0").contains("obs-1");
        verify(gatewayClient, never()).query(anyString(), anyString());
        verify(agentRunRepository, never()).updateContextSummary(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName(
            "above the trigger threshold, older steps are summarized and only recent ones stay verbatim")
    void aboveThresholdSummarizesOlderSteps() {
        agentProperties.setCompactionTriggerSteps(2);
        agentProperties.setCompactionKeepRecentSteps(1);
        when(gatewayClient.query(anyString(), anyString()))
                .thenReturn(
                        new GatewayChatResponse(
                                "rolled-up summary", "gpt-4o", "openai", null, 1, 1, 2, 5L, "r"));
        List<AgentStep> steps = List.of(step(0, "obs-0"), step(1, "obs-1"), step(2, "obs-2"));

        String transcript = compactor.buildTranscript(runWith(null, 0), steps);

        assertThat(transcript).contains("Earlier progress (summarized): rolled-up summary");
        assertThat(transcript).contains("obs-2");
        assertThat(transcript).doesNotContain("obs-0").doesNotContain("obs-1");
        verify(agentRunRepository).updateContextSummary(1L, "rolled-up summary", 2);
    }

    @Test
    @DisplayName(
            "only newly aged-out steps are sent to the summarizer, combined with the existing summary")
    void onlyNewlyAgedStepsAreResummarized() {
        agentProperties.setCompactionTriggerSteps(2);
        agentProperties.setCompactionKeepRecentSteps(1);
        when(gatewayClient.query(anyString(), anyString()))
                .thenReturn(
                        new GatewayChatResponse("updated summary", "gpt-4o", "openai", null, 1, 1, 2, 5L, "r"));
        List<AgentStep> steps =
                List.of(step(0, "obs-0"), step(1, "obs-1"), step(2, "obs-2"), step(3, "obs-3"));

        compactor.buildTranscript(runWith("prior summary", 1), steps);

        verify(gatewayClient).query(contains("prior summary"), anyString());
        verify(gatewayClient)
                .query(
                        org.mockito.ArgumentMatchers.argThat(p -> p.contains("obs-1") && p.contains("obs-2")),
                        anyString());
        verify(gatewayClient, never()).query(contains("obs-0"), anyString());
        verify(agentRunRepository).updateContextSummary(eq(1L), eq("updated summary"), eq(3));
    }

    @Test
    @DisplayName("a failed summarization call keeps the prior summary instead of losing it")
    void failedSummarizationKeepsPriorSummary() {
        agentProperties.setCompactionTriggerSteps(2);
        agentProperties.setCompactionKeepRecentSteps(1);
        when(gatewayClient.query(anyString(), anyString()))
                .thenReturn(
                        new GatewayChatResponse(
                                null, null, "llm-gateway-core", "boom", null, null, null, 0L, null));
        List<AgentStep> steps = List.of(step(0, "obs-0"), step(1, "obs-1"), step(2, "obs-2"));

        String transcript = compactor.buildTranscript(runWith("prior summary", 0), steps);

        assertThat(transcript).contains("Earlier progress (summarized): prior summary");
        verify(agentRunRepository).updateContextSummary(1L, "prior summary", 2);
    }
}
