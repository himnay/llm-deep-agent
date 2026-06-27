package com.org.llm.deepagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spring's {@code ResponseBodyEmitter.Handler} (needed to intercept what an emitter actually sends)
 * is package-private in spring-webmvc, so these tests exercise {@link RunEventBroadcaster} through
 * the public {@link SseEmitter} lifecycle instead: completing an emitter should remove it from the
 * broadcaster's internal map via the {@code onCompletion} callback {@code subscribe()} registers.
 * If that removal didn't happen, a later {@code publish()} attempting to send on an
 * already-completed emitter would throw {@code IllegalStateException} — exactly the failure mode
 * these tests would catch.
 */
class RunEventBroadcasterTest {

    private final RunEventBroadcaster broadcaster = new RunEventBroadcaster();

    @Test
    @DisplayName("subscribe() returns a distinct emitter on every call, even for the same run")
    void subscribeReturnsDistinctEmitters() {
        SseEmitter first = broadcaster.subscribe(7L);
        SseEmitter second = broadcaster.subscribe(7L);

        assertThat(first).isNotNull().isNotSameAs(second);
    }

    @Test
    @DisplayName("publish() with no subscribers for that run is a silent no-op")
    void publishWithNoSubscribersIsNoOp() {
        assertThatCode(() -> broadcaster.publish(123L, "step", "payload")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("complete() with no subscribers for that run is a silent no-op")
    void completeWithNoSubscribersIsNoOp() {
        assertThatCode(() -> broadcaster.complete(123L, "done", "final")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() does not throw while a subscriber is still connected")
    void publishDoesNotThrowForAConnectedSubscriber() {
        broadcaster.subscribe(7L);

        assertThatCode(() -> broadcaster.publish(7L, "step", "payload")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("complete() removes its emitters from the broadcaster before completing them")
    void completeRemovesEmittersBeforeCompletingThem() {
        broadcaster.subscribe(7L);

        broadcaster.complete(7L, "done", "final");

        // complete() must remove its emitters from the map *before* calling SseEmitter.complete() on
        // them — otherwise a later publish()/complete() would call send() on an already-completed
        // emitter, which throws IllegalStateException regardless of whether a handler is attached.
        assertThatCode(() -> broadcaster.publish(7L, "step", "payload")).doesNotThrowAnyException();
    }
}
