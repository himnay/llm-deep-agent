package com.org.llm.deepagent.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Secures the outbound MCP connections to llm-mcp's downstream servers. Per connection, attaches
 * one of two {@code Authorization} schemes:
 *
 * <ul>
 *   <li>{@code deployment} — Keycloak client-credentials access token (fetched via {@link
 *       McpTokenService}), since llm-mcp's deployment-service is itself an OAuth2 resource server.
 *   <li>every other connection — the legacy shared bearer token ({@code mcp.auth-token}, bound from
 *       {@code MCP_AUTH_TOKEN}).
 * </ul>
 * <p>
 * In Spring AI 2.0.x the Streamable-HTTP transport is configured through a {@link
 * McpClientCustomizer}, the only customizer hook that receives the connection name.
 */
@Configuration
public class McpClientSecurityConfig {

    private static final String OAUTH2_CONNECTION_NAME = "deployment";

    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpAuthTransportCustomizer(
            McpProperties properties, McpTokenService mcpTokenService) {
        McpSyncHttpClientRequestCustomizer oauth2Customizer = oauth2RequestCustomizer(mcpTokenService);
        McpSyncHttpClientRequestCustomizer staticTokenCustomizer =
                staticBearerRequestCustomizer(properties);

        return (name, transportBuilder) ->
                transportBuilder.httpRequestCustomizer(
                        OAUTH2_CONNECTION_NAME.equals(name) ? oauth2Customizer : staticTokenCustomizer);
    }

    private McpSyncHttpClientRequestCustomizer staticBearerRequestCustomizer(
            McpProperties properties) {
        return (builder, method, endpoint, body, context) -> {
            String token = properties.getAuthToken();
            if (StringUtils.hasText(token)) {
                builder.header("Authorization", "Bearer " + token);
            }
        };
    }

    private McpSyncHttpClientRequestCustomizer oauth2RequestCustomizer(
            McpTokenService mcpTokenService) {
        return (builder, method, endpoint, body, context) ->
                builder.header("Authorization", "Bearer " + mcpTokenService.getToken());
    }
}
