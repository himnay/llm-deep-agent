package com.org.llm.deepagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorConfigTest {

    @Test
    @DisplayName(
            "agentRunExecutor() propagates the calling thread's MDC context onto the worker thread")
    void executorPropagatesMdcContextOntoWorkerThread() throws InterruptedException {
        Executor executor = new AgentExecutorConfig().agentRunExecutor();
        AtomicReference<String> seenOnWorkerThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        MDC.put("runId", "42");
        try {
            executor.execute(
                    () -> {
                        seenOnWorkerThread.set(MDC.get("runId"));
                        done.countDown();
                    });
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            MDC.clear();
        }

        assertThat(seenOnWorkerThread.get()).isEqualTo("42");
    }

    @Test
    @DisplayName(
            "the worker thread's MDC is cleared again once the task finishes (no leakage between tasks)")
    void executorClearsMdcAfterTaskCompletes() throws InterruptedException {
        Executor executor = new AgentExecutorConfig().agentRunExecutor();
        AtomicReference<String> seenOnSecondTask = new AtomicReference<>("not-run");
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);

        MDC.put("runId", "42");
        try {
            executor.execute(first::countDown);
            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            MDC.clear();
        }

        executor.execute(
                () -> {
                    seenOnSecondTask.set(MDC.get("runId"));
                    second.countDown();
                });
        assertThat(second.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(seenOnSecondTask.get()).isNull();
    }
}
