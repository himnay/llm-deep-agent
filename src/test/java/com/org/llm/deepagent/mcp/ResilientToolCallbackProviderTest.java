package com.org.llm.deepagent.mcp;

import com.org.llm.deepagent.exception.McpToolCallException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("getToolCallbacks() wraps every delegate callback while preserving its tool definition")
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
    @DisplayName("call() delegates to the underlying tool and returns its result on success")
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
    @DisplayName("call() wraps a failing delegate's exception in McpToolCallException")
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
    @DisplayName("call() returns a structured error message when the circuit breaker is open")
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
    @DisplayName("a tool with no configured resilience mapping falls back to the mcp-unknown circuit breaker instance")
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
    @DisplayName("the call() overload accepting a ToolContext also delegates to the underlying tool")
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
