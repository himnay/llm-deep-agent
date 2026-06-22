package com.org.llm.orchestrator.config;

import io.github.mweirauch.micrometer.jvm.extras.ProcessMemoryMetrics;
import io.github.mweirauch.micrometer.jvm.extras.ProcessThreadMetrics;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer Observation configuration — same pattern as llm-gateway-core's.
 *
 * <p>Registers the {@link ObservedAspect}/{@link TimedAspect} so {@code @Observed}/{@code @Timed}
 * annotated methods (e.g. {@code AgentLoopExecutor.run}) get spans + Prometheus timers without
 * boilerplate, and exposes process-level JVM metrics for the shared Grafana dashboard.
 */
@Configuration
public class ObservabilityConfig {

  @Bean
  ObservedAspect observedAspect(ObservationRegistry registry) {
    return new ObservedAspect(registry);
  }

  @Bean
  TimedAspect timedAspect(MeterRegistry registry) {
    return new TimedAspect(registry);
  }

  /** Linux-only (backed by {@code /proc}); no-op gauges on macOS/Windows. */
  @Bean
  MeterBinder processMemoryMetrics() {
    return new ProcessMemoryMetrics();
  }

  /** Linux-only, same as {@link #processMemoryMetrics()}. */
  @Bean
  MeterBinder processThreadMetrics() {
    return new ProcessThreadMetrics();
  }
}
