package com.org.llm.deepagent.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for calling llm-gateway-core (prefix {@code gateway}).
 */
@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /**
     * llm-gateway-core base URL including its {@code /llm/v1} base path.
     */
    private String baseUrl = "http://localhost:8080/llm/v1";

    /**
     * Provider the gateway should route the planner/tool LLM calls to.
     */
    private String provider = "openai";

    /**
     * Per-call timeout (seconds) for blocking gateway requests.
     */
    private int timeoutSeconds = 60;
}
