package com.org.llm.deepagent.agent;

/**
 * A cheap, steps-free projection of a run's row — re-read once per loop iteration so {@code
 * continueRun} can notice an externally-issued cancellation or a token budget breach without paying
 * the cost of reloading the full {@link AgentRun} (including its step list) every turn.
 */
public record RunControlState(AgentRunStatus status, int totalTokensUsed) {}
