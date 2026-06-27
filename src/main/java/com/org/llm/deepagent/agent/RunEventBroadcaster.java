package com.org.llm.deepagent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, per-run fan-out of Server-Sent Events for {@code GET /agent/run/{runId}/events} — the
 * GoF <b>Observer</b> for run progress. Lives entirely in process memory: a subscriber must be
 * connected while the run is executing to see live events (it can always fall back to polling
 * {@code GET /agent/run/{runId}} for the durable, persisted state).
 */
@Slf4j
@Component
public class RunEventBroadcaster {

    private static final Duration SUBSCRIPTION_TIMEOUT = Duration.ofMinutes(30);

    private final Map<Long, List<SseEmitter>> emittersByRunId = new ConcurrentHashMap<>();

    /**
     * Registers a new subscriber for a run's events; auto-removed on completion, timeout or error.
     */
    public SseEmitter subscribe(long runId) {
        SseEmitter emitter = new SseEmitter(SUBSCRIPTION_TIMEOUT.toMillis());
        emittersByRunId.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(runId, emitter));
        emitter.onTimeout(() -> removeEmitter(runId, emitter));
        emitter.onError(ex -> removeEmitter(runId, emitter));
        return emitter;
    }

    /**
     * Sends a named, JSON-serialized event to every current subscriber of a run.
     */
    public void publish(long runId, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByRunId.get(runId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException e) {
                log.debug("RUN_EVENT_BROADCASTER | subscriber disconnected | run={}", runId);
                removeEmitter(runId, emitter);
            }
        }
    }

    /**
     * Publishes a final event and closes every subscriber's connection for a run.
     */
    public void complete(long runId, String eventName, Object payload) {
        publish(runId, eventName, payload);
        List<SseEmitter> emitters = emittersByRunId.remove(runId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }

    private void removeEmitter(long runId, SseEmitter emitter) {
        emittersByRunId.computeIfPresent(
                runId,
                (id, emitters) -> {
                    emitters.remove(emitter);
                    return emitters.isEmpty() ? null : emitters;
                });
    }
}
