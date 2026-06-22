package com.org.llm.orchestrator.routing;

/** Carries per-run identity through a single {@link RoutingStrategy} dispatch. */
public record AgentContext(Long runId, String sessionId) {}
