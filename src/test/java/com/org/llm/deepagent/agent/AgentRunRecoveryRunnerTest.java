package com.org.llm.deepagent.agent;

import com.org.llm.deepagent.persistence.AgentRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.Mockito.*;

class AgentRunRecoveryRunnerTest {

    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final AgentLoopExecutor agentLoopExecutor = mock(AgentLoopExecutor.class);
    private final Executor synchronousExecutor = Runnable::run;
    private final AgentRunRecoveryRunner runner =
            new AgentRunRecoveryRunner(agentRunRepository, agentLoopExecutor, synchronousExecutor);

    @Test
    @DisplayName("does nothing when no runs were left RUNNING by a prior process")
    void noOpWhenNothingOrphaned() throws Exception {
        when(agentRunRepository.findAllRunningRunIds()).thenReturn(List.of());

        runner.run(null);

        verify(agentLoopExecutor, never()).continueRun(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("resubmits every orphaned RUNNING run to continueRun")
    void resubmitsEveryOrphanedRun() throws Exception {
        when(agentRunRepository.findAllRunningRunIds()).thenReturn(List.of(3L, 7L));

        runner.run(null);

        verify(agentLoopExecutor).continueRun(3L);
        verify(agentLoopExecutor).continueRun(7L);
    }
}
