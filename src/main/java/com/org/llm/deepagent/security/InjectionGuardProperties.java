package com.org.llm.deepagent.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Externalised prompt-injection pattern catalogue for {@link PromptInjectionGuard}.
 * Patterns are loaded from {@code app.security.injection-guard.patterns} in application.yaml,
 * so new attack signatures can be added or tuned without code changes or redeployment.
 * Any value present in configuration REPLACES the default list entirely.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security.injection-guard")
public class InjectionGuardProperties {

    private boolean enabled = true;
    private String blockMessage = "Your request contains disallowed instructions and cannot be processed.";

    /**
     * Regex patterns that trigger a block. Matched case-insensitively.
     * Override via {@code app.security.injection-guard.patterns[0]=...} in application.yaml.
     */
    private List<String> patterns = List.of(
            // Instruction override
            "(?i)ignore\\s+(?:all\\s+)?(?:previous|prior|above|all)\\s+instructions?",
            "(?i)ignore\\s+your\\s+(system\\s+)?prompt",
            "(?i)forget\\s+(your|all|previous)\\s+(instructions?|context|training)",
            "(?i)disregard\\s+(all|previous|your)\\s+instructions?",
            "(?i)override\\s+(the\\s+)?(system|previous|all)\\s+(prompt|instructions?)",
            // Roleplay / persona hijack
            "(?i)you\\s+are\\s+now\\s+(?:DAN|an?\\s+AI\\s+without)",
            "(?i)act\\s+as\\s+if\\s+you\\s+(have\\s+no|are\\s+without)\\s+(restrictions?|rules?)",
            "(?i)pretend\\s+(you\\s+are|to\\s+be)\\s+",
            "(?i)roleplay\\s+as\\s+",
            "(?i)simulate\\s+(a|an|the)\\s+.{0,30}(AI|model|assistant|bot)",
            // System prompt exfiltration
            "(?i)(print|reveal|show|output|repeat|display)\\s+(your\\s+)?(system\\s+)?prompt",
            "(?i)(print|repeat|show)\\s+(all\\s+)?(your\\s+)?instructions?",
            "(?i)what\\s+(are|were)\\s+your\\s+instructions?",
            // Structural delimiter injection
            "(?i)\\[\\s*SYSTEM\\s*\\]",
            "(?i)<\\s*system\\s*>",
            "(?i)###\\s*(instruction|system|prompt)",
            "(?i)```\\s*(system|instructions?)",
            // Jailbreak keywords
            "(?i)jailbreak",
            "(?i)developer\\s+mode",
            "(?i)DAN\\s+mode"
    );
}
