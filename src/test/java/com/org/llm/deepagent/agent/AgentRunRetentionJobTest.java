package com.org.llm.deepagent.agent;

import com.org.llm.deepagent.persistence.AgentRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentRunRetentionJobTest {

    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final AgentProperties agentProperties = new AgentProperties();
    private final AgentRunRetentionJob job =
            new AgentRunRetentionJob(agentRunRepository, agentProperties);

    @Test
    @DisplayName("pruneExpiredRuns() deletes runs completed before now minus agent.retention-days")
    void prunesRunsOlderThanRetentionWindow() {
        agentProperties.setRetentionDays(7);
        when(agentRunRepository.deleteTerminalRunsCompletedBefore(any())).thenReturn(3);

        job.pruneExpiredRuns();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(agentRunRepository).deleteTerminalRunsCompletedBefore(cutoffCaptor.capture());
        Instant expectedCutoff = Instant.now().minus(Duration.ofDays(7));
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }
}
