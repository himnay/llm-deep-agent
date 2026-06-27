package com.org.llm.deepagent.agent;

import com.org.llm.deepagent.persistence.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Prunes terminal top-level runs (and, via {@code ON DELETE CASCADE}, their steps/tasks/
 * artifacts/sub-agents) older than {@code agent.retention-days} — otherwise {@code agent_run} and
 * its child tables grow unbounded, made worse by sub-agent delegation multiplying row count per
 * top-level run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunRetentionJob {

    private final AgentRunRepository agentRunRepository;
    private final AgentProperties agentProperties;

    /**
     * Runs once a day; the cron schedule itself is overridable via {@code
     * agent.retention-cron-schedule}.
     */
    @Scheduled(cron = "${agent.retention-cron-schedule:0 0 3 * * *}")
    public void pruneExpiredRuns() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(agentProperties.getRetentionDays()));
        int deleted = agentRunRepository.deleteTerminalRunsCompletedBefore(cutoff);
        if (deleted > 0) {
            log.info(
                    "AGENT_RUN_RETENTION | deleted {} top-level run(s) (and their sub-trees) completed before {}",
                    deleted,
                    cutoff);
        }
    }
}
