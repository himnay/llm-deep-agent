package com.org.llm.deepagent.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-credentials settings for this service's own identity ({@code llm-orchestrator-client}) in
 * the shared "llm-gateway" Keycloak realm — used for every outbound call to llm-gateway-core and
 * llm-rag-pipeline (prefix {@code platform.auth}).
 */
@Data
@ConfigurationProperties(prefix = "platform.auth")
public class PlatformAuthProperties {

  private String tokenUri =
      "http://localhost:8081/realms/llm-gateway/protocol/openid-connect/token";
  private String clientId = "llm-orchestrator-client";
  private String clientSecret = "llm-orchestrator-dev-secret";
}
