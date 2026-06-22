package com.org.llm.orchestrator.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-credentials settings for the Keycloak realm that issues access tokens this service
 * presents to OAuth2-protected MCP servers (currently: llm-mcp's {@code deployment-service}, on its
 * own {@code org-mcp} realm — a different realm from the platform-wide {@code llm-gateway} one used
 * for llm-gateway-core/llm-rag-pipeline).
 */
@Data
@ConfigurationProperties(prefix = "mcp.oauth2")
public class McpOAuth2Properties {

  private String tokenUri = "http://localhost:8180/realms/org-mcp/protocol/openid-connect/token";
  private String clientId = "llm-orchestrator";
  private String clientSecret = "llm-orchestrator-secret";
}
