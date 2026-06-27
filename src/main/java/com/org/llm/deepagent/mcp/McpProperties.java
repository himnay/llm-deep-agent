package com.org.llm.deepagent.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static bearer token presented to every MCP server that isn't OAuth2-protected.
 */
@Data
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private String authToken = "";
}
