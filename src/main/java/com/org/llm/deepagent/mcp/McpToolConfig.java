package com.org.llm.deepagent.mcp;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wires every configured llm-mcp server into a single, resilient {@link ToolCallbackProvider} — the
 * same shape every other Spring AI {@code @Tool} (gateway/RAG-backed) lives in, so the agent loop's
 * planner LLM sees one flat tool catalogue regardless of where a tool actually executes.
 *
 * <p>Tool-to-server names below mirror llm-mcp's published catalogue (see
 * ~/REPOS/LLM/llm-mcp/README.md) so the resilience4j instance names in {@code application.yaml}
 * line up even for servers not enabled by default.
 */
@Slf4j
@Configuration
public class McpToolConfig {

    private static final Map<String, String> TOOL_SERVER =
            Map.ofEntries(
                    Map.entry("applyLeave", "mcp-hr"),
                    Map.entry("findReplacement", "mcp-hr"),
                    Map.entry("getDeployments", "mcp-deployment"),
                    Map.entry("getDeployment", "mcp-deployment"),
                    Map.entry("createDeployment", "mcp-deployment"),
                    Map.entry("assignOwner", "mcp-deployment"),
                    Map.entry("rescheduleDeployment", "mcp-deployment"),
                    Map.entry("cancelDeployment", "mcp-deployment"),
                    Map.entry("getNotifications", "mcp-notification"),
                    Map.entry("sendNotification", "mcp-notification"),
                    Map.entry("getRepository", "mcp-github"),
                    Map.entry("getCommitHistory", "mcp-github"),
                    Map.entry("getCommitMetrics", "mcp-github"),
                    Map.entry("listBranches", "mcp-github"),
                    Map.entry("getPullRequests", "mcp-github"),
                    Map.entry("getIssues", "mcp-github"),
                    Map.entry("getContributors", "mcp-github"),
                    Map.entry("getWorkflowRuns", "mcp-github"),
                    Map.entry("getReleases", "mcp-github"),
                    Map.entry("searchRepositories", "mcp-github"),
                    Map.entry("getCodeFrequency", "mcp-github"),
                    Map.entry("createIssue", "mcp-github"),
                    Map.entry("summarizeRepositoryHealth", "mcp-github"),
                    Map.entry("listEmails", "mcp-gmail"),
                    Map.entry("getEmail", "mcp-gmail"),
                    Map.entry("searchEmails", "mcp-gmail"),
                    Map.entry("getEmailThread", "mcp-gmail"),
                    Map.entry("getGmailProfile", "mcp-gmail"),
                    Map.entry("listLabels", "mcp-gmail"),
                    Map.entry("getEmailsByLabel", "mcp-gmail"),
                    Map.entry("markAsRead", "mcp-gmail"),
                    Map.entry("markAsUnread", "mcp-gmail"),
                    Map.entry("createDraft", "mcp-gmail"),
                    Map.entry("sendEmail", "mcp-gmail"),
                    Map.entry("deleteEmail", "mcp-gmail"),
                    Map.entry("searchFlights", "mcp-travel"),
                    Map.entry("getAirportInfo", "mcp-travel"));

    private static String clientName(McpSyncClient client) {
        try {
            return client.getClientInfo().name();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Attempts to initialize each MCP client individually at startup. Clients whose downstream server
     * is unreachable are skipped rather than failing the whole boot, so the orchestrator starts with
     * whatever subset of tools is currently available.
     */
    @Bean
    @Primary
    public ToolCallbackProvider resilientToolCallbackProvider(
            ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Value("${agent.step-timeout-seconds:30}") int toolTimeoutSeconds) {

        List<McpSyncClient> allClients = mcpSyncClientsProvider.stream().flatMap(List::stream).toList();

        List<McpSyncClient> available = new ArrayList<>();
        for (McpSyncClient client : allClients) {
            String name = clientName(client);
            try {
                client.initialize();
                available.add(client);
                log.info("MCP_TOOL | server connected | {}", name);
            } catch (Exception e) {
                log.warn("MCP_TOOL | server unavailable, skipping | {} | {}", name, e.getMessage());
            }
        }

        if (available.isEmpty()) {
            log.warn(
                    "MCP_TOOL | no MCP servers are reachable — the agent will have no MCP tools available");
        }

        SyncMcpToolCallbackProvider delegate =
                SyncMcpToolCallbackProvider.builder().mcpClients(available).build();

        return new ResilientToolCallbackProvider(
                delegate, circuitBreakerRegistry, retryRegistry, toolTimeoutSeconds, TOOL_SERVER);
    }
}
