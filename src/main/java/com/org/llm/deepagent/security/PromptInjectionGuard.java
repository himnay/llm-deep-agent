package com.org.llm.deepagent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Guards incoming user prompts against injection before they enter the ReAct loop.
 *
 * <p>The ReAct loop executes many LLM calls and tool invocations over many turns — an injected
 * prompt that reaches the planner can hijack the entire run. This guard checks the initial user
 * prompt in {@code AgentLoopExecutor.startRun} and rejects the run immediately if an injection
 * pattern is detected, before any state is persisted or any LLM call is made.</p>
 *
 * <p>Patterns are externalised in {@link InjectionGuardProperties}
 * ({@code app.security.injection-guard.patterns}) so new attack signatures can be added
 * in configuration without code changes.</p>
 */
@Slf4j
@Component
public class PromptInjectionGuard {

    private final List<Pattern> compiledPatterns;
    private final boolean enabled;
    private final String blockMessage;

    public PromptInjectionGuard(InjectionGuardProperties properties) {
        this.enabled = properties.isEnabled();
        this.blockMessage = properties.getBlockMessage();
        this.compiledPatterns = properties.getPatterns().stream()
                .flatMap(regex -> {
                    try {
                        return java.util.stream.Stream.of(Pattern.compile(regex));
                    } catch (PatternSyntaxException ex) {
                        log.error("SECURITY | invalid injection pattern skipped | regex='{}' | error={}", regex, ex.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();
        log.info("SECURITY | PromptInjectionGuard ready | patterns={} enabled={}", compiledPatterns.size(), enabled);
    }

    /**
     * Returns {@code true} if the text matches none of the configured injection patterns.
     */
    public boolean isSafe(String text) {
        if (!enabled || text == null || text.isBlank()) return true;
        return compiledPatterns.stream().noneMatch(p -> p.matcher(text).find());
    }

    /**
     * Validates the user's prompt before it enters the agent loop.
     * Returns {@code false} if an injection pattern is detected.
     */
    public boolean isQuerySafe(String userPrompt) {
        boolean safe = isSafe(userPrompt);
        if (!safe) {
            log.warn("SECURITY | Injection pattern detected in agent prompt — rejecting run");
        }
        return safe;
    }

    /** The configured rejection message. */
    public String blockMessage() {
        return blockMessage;
    }
}
