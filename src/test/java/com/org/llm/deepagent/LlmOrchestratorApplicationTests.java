package com.org.llm.deepagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full application-context smoke test. The MCP connections in {@code application.yaml} point at
 * localhost ports that aren't running here — {@code McpToolConfig} is designed to degrade
 * gracefully (skip unreachable servers) rather than fail boot, so this exercises that path too.
 */
@Testcontainers
@SpringBootTest
class LlmOrchestratorApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Test
    @DisplayName("the full Spring application context loads with MCP servers unreachable")
    void contextLoads(ApplicationContext context) {
        assertThat(context.getBean("filterChain")).isNotNull();
        assertThat(context.getBean("resilientToolCallbackProvider")).isNotNull();
        assertThat(context.getBean("mcpAuthTransportCustomizer")).isNotNull();
    }
}
