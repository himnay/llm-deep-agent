package com.org.llm.deepagent.mcp;

import com.org.llm.deepagent.exception.McpToolCallException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResilientToolCallbackProviderTest {

    private final CircuitBreakerRegistry circuitBreakerRegistry =
            CircuitBreakerRegistry.of(
                    CircuitBreakerConfig.custom().slidingWindowSize(2).failureRateThreshold(50).build());
    private final RetryRegistry retryRegistry =
            RetryRegistry.of(
                    RetryConfig.custom().maxAttempts(1).waitDuration(Duration.ofMillis(1)).build());

    private ToolCallback toolNamed(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @Test
    void wrapsEveryDelegateCallbackAndPreservesItsToolDefinition() {
        ToolCallback raw = toolNamed("getDeployments");
        ToolCallbackProvider delegate = mock(ToolCallbackProvider.class);
        when(delegate.getToolCallbacks()).thenReturn(new ToolCallback[]{raw});

        ResilientToolCallbackProvider provider =
                new ResilientToolCallbackProvider(
                        delegate,
                        circuitBreakerRegistry,
                        retryRegistry,
                        5,
                        Map.of("getDeployments", "deployment"));

        ToolCallback[] wrapped = provider.getToolCallbacks();

        assertThat(wrapped).hasSize(1);
        assertThat(wrapped[0].getToolDefinition().name()).isEqualTo("getDeployments");
    }

    @Test
    void callDelegatesAndReturnsTheUnderlyingResultOnSuccess() {
        ToolCallback raw = toolNamed("getDeployments");
        when(raw.call("{}")).thenReturn("[]");
        ToolCallbackProvider delegate = mock(ToolCallbackProvider.class);
        when(delegate.getToolCallbacks()).thenReturn(new ToolCallback[]{raw});

        ResilientToolCallbackProvider provider =
                new ResilientToolCallbackProvider(
                        delegate,
                        circuitBreakerRegistry,
                        retryRegistry,
                        5,
                        Map.of("getDeployments", "deployment"));

        String result = provider.getToolCallbacks()[0].call("{}");

        assertThat(result).isEqualTo("[]");
    }

    @Test
    void callWrapsAFailingDelegateInMcpToolCallException() {
        ToolCallback raw = toolNamed("getDeployments");
        when(raw.call("{}")).thenThrow(new RuntimeException("downstream boom"));
        ToolCallbackProvider delegate = mock(ToolCallbackProvider.class);
        when(delegate.getToolCallbacks()).thenReturn(new ToolCallback[]{raw});

        ResilientToolCallbackProvider provider =
                new ResilientToolCallbackProvider(
                        delegate,
                        circuitBreakerRegistry,
                        retryRegistry,
                        5,
                        Map.of("getDeployments", "deployment"));

        assertThatThrownBy(() -> provider.getToolCallbacks()[0].call("{}"))
                .isInstanceOf(McpToolCallException.class)
                .hasMessageContaining("downstream boom");
    }

    @Test
    void callReturnsAStructuredErrorWhenTheCircuitIsOpen() {
        ToolCallback raw = toolNamed("getDeployments");
        when(raw.call("{}")).thenThrow(new RuntimeException("boom"));
        ToolCallbackProvider delegate = mock(ToolCallbackProvider.class);
        when(delegate.getToolCallbacks()).thenReturn(new ToolCallback[]{raw});

        ResilientToolCallbackProvider provider =
                new ResilientToolCallbackProvider(
                        delegate,
                        circuitBreakerRegistry,
                        retryRegistry,
                        5,
                        Map.of("getDeployments", "deployment"));
        ToolCallback wrapped = provider.getToolCallbacks()[0];

        // Force the circuit open by recording enough failures directly against the breaker.
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("deployment");
        breaker.transitionToOpenState();

        String result = wrapped.call("{}");

        assertThat(result).contains("temporarily unavailable").contains("circuit open");
    }

    @Test
    void unknownToolFallsBackToTheMcpUnknownResilienceInstance() {
        ToolCallback raw = toolNamed("someTool");
        ToolCallbackProvider delegate = mock(ToolCallbackProvider.class);
        when(delegate.getToolCallbacks()).thenReturn(new ToolCallback[]{raw});

        ResilientToolCallbackProvider provider =
                new ResilientToolCallbackProvider(
                        delegate, circuitBreakerRegistry, retryRegistry, 5, Map.of());

        provider.getToolCallbacks();

        assertThat(circuitBreakerRegistry.getAllCircuitBreakers())
                .anyMatch(cb -> cb.getName().equals("mcp-unknown"));
    }

    @Test
    void callWithToolContextOverloadAlsoDelegates() {
        ToolCallback raw = toolNamed("getDeployments");
        when(raw.call("{}", null)).thenReturn("[]");
        ToolCallbackProvider delegate = mock(ToolCallbackProvider.class);
        when(delegate.getToolCallbacks()).thenReturn(new ToolCallback[]{raw});

        ResilientToolCallbackProvider provider =
                new ResilientToolCallbackProvider(
                        delegate,
                        circuitBreakerRegistry,
                        retryRegistry,
                        5,
                        Map.of("getDeployments", "deployment"));

        String result = provider.getToolCallbacks()[0].call("{}", null);

        assertThat(result).isEqualTo("[]");
    }
}
