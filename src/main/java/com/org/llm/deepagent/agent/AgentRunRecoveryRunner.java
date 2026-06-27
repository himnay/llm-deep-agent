package com.org.llm.deepagent.agent;

import com.org.llm.deepagent.persistence.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Resumes runs left {@code RUNNING} by a process that crashed or was killed mid-loop. A freshly
 * booting instance can't have any of its own runs in flight yet, so every row still {@code RUNNING}
 * at startup is, by definition, orphaned — safe to resubmit to {@link
 * AgentLoopExecutor#continueRun}, which rebuilds all loop state from the database anyway. (Assumes
 * a single instance of this service runs against a given database; a multi-instance deployment
 * would need a distributed lock here instead.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunRecoveryRunner implements ApplicationRunner {

    private final AgentRunRepository agentRunRepository;
    private final AgentLoopExecutor agentLoopExecutor;
    private final Executor agentRunExecutor;

    @Override
    public void run(ApplicationArguments args) {
        List<Long> orphaned = agentRunRepository.findAllRunningRunIds();
        if (orphaned.isEmpty()) {
            return;
        }
        log.warn(
                "AGENT_RUN_RECOVERY | found {} run(s) left RUNNING by a prior process — resuming them | runIds={}",
                orphaned.size(),
                orphaned);
        for (Long runId : orphaned) {
            agentRunExecutor.execute(() -> agentLoopExecutor.continueRun(runId));
        }
    }
}
