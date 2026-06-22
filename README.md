# LLM Orchestrator

A ReAct-style **agentic orchestration** service: it sits *above* `llm-gateway`, `llm-rag` and
`llm-mcp`, and runs a plan → act → observe loop that chains calls across all three so a single
prompt can answer multi-step questions ("check the deployment status, summarize it, and email the
on-call channel") instead of a single LLM round-trip.

```
                         POST /orchestrator/v1/agent/run
                                       │
                                       ▼
                         ┌─────────────────────────┐
                         │   AgentLoopExecutor      │  plan → act → observe, up to
                         │  (plan/act/observe loop) │  agent.max-iterations turns
                         └────────────┬─────────────┘
                                      │ "what's the next action?"
                     ┌────────────────┼─────────────────────┐
                     ▼                                      │
          ┌─────────────────────┐            RoutingStrategyChain dispatches
          │   llm-gateway-core  │◄───────────  the chosen action to:
          │   (planner LLM)     │
          └─────────────────────┘            ┌──────────────────┬───────────────────┬─────────────────────┐
                                              ▼                  ▼                   ▼
                                    ┌──────────────────┐ ┌───────────────┐ ┌────────────────────┐
                                    │ GatewayLlmRouting │ │  RagRouting   │ │  McpToolRouting     │
                                    │   → llm-gateway   │ │  → llm-rag    │ │  → llm-mcp servers   │
                                    └──────────────────┘ └───────────────┘ └────────────────────┘
                                              │
                                              ▼
                                  agent_run / agent_step (Postgres, audit trail)
```

Every run is persisted: `GET /agent/run/{id}` replays the full step-by-step trace (action taken,
input, observation, planner's reasoning) for debugging or auditing.

---

## Tech Stack

| Concern       | Technology                                                                 |
|---------------|-----------------------------------------------------------------------------|
| Language      | Java 25                                                                      |
| Framework     | Spring Boot 4.1.0, Spring MVC                                               |
| AI            | Spring AI 2.0.0 — MCP client, OpenAI-compatible chat model, JDBC chat memory |
| Persistence   | Spring JDBC (`JdbcTemplate`) + PostgreSQL + Flyway                          |
| Security      | Spring Security OAuth2 Resource Server (Keycloak JWT)                       |
| Resilience    | Resilience4j (retry + circuit breaker, per downstream dependency)           |
| Observability | Actuator + Micrometer + Prometheus + OTLP tracing → Grafana Tempo            |
| API docs      | springdoc-openapi (Swagger UI)                                              |
| Build         | Maven, shared `super-pom` parent + `learning-bom` dependency management     |

No dependency or plugin version is hardcoded in this module's `pom.xml` — all versions come from
`super-pom`/`learning-bom` properties, matching the convention in `llm-gateway`, `llm-chat`,
`llm-rag` and `llm-mcp`.

---

## Design Patterns (GoF)

| Pattern                     | Where                                                          | Role                                                                                              |
|------------------------------|-----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| **Strategy**                 | `RoutingStrategy` + its 3 implementations                       | Each downstream system (gateway LLM, RAG, MCP tools) is a swappable strategy for "act"               |
| **Chain of Responsibility**  | `RoutingStrategyChain`                                          | Tries each registered `RoutingStrategy` in turn; first one whose `supports()` matches handles the step |
| **Facade**                   | `GatewayClient`, `RagClient`                                    | Hide the HTTP/auth/resilience plumbing behind a one-method call (`chat()`, `retrieve()`, `generate()`) |
| **Proxy (protection)**       | `ResilientToolCallbackProvider`                                 | Wraps every MCP `ToolCallback` with retry + circuit breaker; an OPEN circuit returns a structured error instead of a doomed call |
| **Template Method (loop)**   | `AgentLoopExecutor.run`                                         | Fixed plan→act→observe→repeat skeleton; the "act" step varies per `RoutingStrategy`                  |
| **Memento**                  | `agent_run`/`agent_step` tables                                 | Every step's input/observation is externalised so a run can be replayed via `GET /agent/run/{id}`     |
| **Singleton**                | All Spring beans                                                | One shared, stateless instance per container                                                          |

---

## The Agent Loop

1. **Plan** — `AgentLoopExecutor` asks the planner LLM (a call to `llm-gateway-core`, not OpenAI
   directly) for the single next action, given the original prompt and the transcript so far. The
   planner must respond with strict JSON: `{"action": "...", "toolName": "...", "input": "...", "reasoning": "..."}`.
2. **Act** — `RoutingStrategyChain` dispatches that action:
   - `GATEWAY_LLM` → a plain LLM call via `llm-gateway-core`.
   - `RAG_RETRIEVE` → grounded source chunks/citations from `llm-rag-pipeline`, no generation.
   - `RAG_GENERATE` → a full grounded answer with a faithfulness/insufficient-context check.
   - `MCP_TOOL` → invokes a named tool exposed by any configured `llm-mcp` server.
   - `FINAL_ANSWER` → ends the loop immediately.
3. **Observe** — the strategy's result (or an error message, on failure) is appended to the
   transcript and fed into the next planning turn.
4. Repeat until `FINAL_ANSWER` or `agent.max-iterations` (default 6) is reached — at which point
   the last observation is returned as a best-effort answer and the run is marked `INCOMPLETE`.

Unparseable planner output (e.g. the model ignored the JSON instruction) is treated as a
`FINAL_ANSWER` rather than crashing the loop — the raw text becomes the answer.

---

## API

### `POST /agent/run`

```bash
curl -s http://localhost:8090/orchestrator/v1/agent/run \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What is the current status of the payments-api deployment?", "sessionId": "demo-1"}'
```

```json
{
  "runId": 7,
  "sessionId": "demo-1",
  "prompt": "What is the current status of the payments-api deployment?",
  "status": "COMPLETED",
  "finalAnswer": "payments-api is SCHEDULED for rollout at 14:00 UTC.",
  "steps": [
    {
      "stepIndex": 0,
      "action": "MCP_TOOL",
      "toolName": "getDeployments",
      "input": "{\"service\":\"payments-api\"}",
      "observation": "[{\"id\":42,\"status\":\"SCHEDULED\"}]",
      "reasoning": "Need the current deployment record before answering."
    }
  ],
  "createdAt": "2026-06-22T05:40:00Z",
  "completedAt": "2026-06-22T05:40:04Z"
}
```

Blocks for the whole run (no streaming yet — see Known Limitations).

### `GET /agent/run/{runId}`

Returns the same shape for a previously persisted run; `404` if the id doesn't exist.

Both endpoints require a valid Keycloak-issued bearer token (any role in the `llm-gateway` realm —
see Security below). Swagger UI: `http://localhost:8090/orchestrator/v1/swagger-ui.html`.

---

## Security — Keycloak / OAuth2

This service is both:
- a **resource server**, validating inbound JWTs against the shared `llm-gateway` Keycloak realm
  (started from `llm-gateway`'s `docker-compose.yml` — start that first), using client
  `llm-orchestrator-client`;
- a **client**, minting its own client-credentials token (cached by `PlatformTokenService`) to call
  `llm-gateway-core` and `llm-rag-pipeline`, and a separate token (`McpTokenService`, against
  llm-mcp's own `org-mcp` realm) for the `deployment-service` MCP server.

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/llm-gateway/protocol/openid-connect/token \
  -d grant_type=password -d client_id=llm-gateway-client \
  -d username=dev-user -d password=devpassword | jq -r .access_token)
```

---

## Configuration

| Property                                | Env var                       | Default                                                      |
|------------------------------------------|--------------------------------|---------------------------------------------------------------|
| `server.port`                            | `SERVER_PORT`                  | `8090`                                                         |
| `spring.datasource.url`                  | `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` | `jdbc:postgresql://localhost:5432/spring_ai`     |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `KEYCLOAK_ISSUER_URI` | `http://localhost:8081/realms/llm-gateway`             |
| `gateway.base-url`                       | `GATEWAY_BASE_URL`             | `http://localhost:8080/llm/v1`                                 |
| `rag.base-url`                           | `RAG_BASE_URL`                 | `http://localhost:8081/api/v1`                                 |
| `platform.auth.client-secret`            | `PLATFORM_OAUTH_CLIENT_SECRET` | `llm-orchestrator-dev-secret`                                   |
| `mcp.oauth2.client-secret`               | `MCP_OAUTH2_CLIENT_SECRET`     | `llm-orchestrator-secret`                                       |
| `agent.max-iterations`                   | `AGENT_MAX_ITERATIONS`         | `6`                                                             |
| `agent.step-timeout-seconds`             | `AGENT_STEP_TIMEOUT_SECONDS`   | `30`                                                            |

MCP server connections (`spring.ai.mcp.client.streamable-http.connections.*`) and Resilience4j
instances (`gateway`, `rag`, `mcp-deployment`, `mcp-github`, `mcp-unknown`) are listed in full in
`application.yaml`.

> ⚠ The default `RAG_BASE_URL` (`:8081`) and the default Keycloak admin/token port
> (also `:8081`, per `llm-gateway`'s `docker-compose.yml`) collide if both are run on the host
> network at once — override one of them via env var when running the full platform locally.

---

## Running

### 1. Start the shared Keycloak realm (from the `llm-gateway` repo)

```bash
cd ../llm-gateway && docker compose up -d keycloak
```

### 2. Start this service's Postgres

```bash
docker compose up -d postgres
```

### 3. Build and run

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

Or via Docker:

```bash
docker compose up --build
```

---

## Testing

```bash
./mvnw test                 # unit tests (routing strategies, agent loop, controller) + Testcontainers Postgres repository test
./mvnw verify                # adds the JaCoCo 70% instruction-coverage gate
./mvnw verify -Psecurity-scan  # adds an OWASP dependency-check pass
```

Spotless (`google-java-format`) must run under JDK 21 — `JAVA_HOME=<jdk21> ./mvnw spotless:apply`.

---

## Known Limitations (v1)

- **No semantic/vector-based MCP tool selection.** Every connected MCP server's tools are exposed
  flat to the planner via a text catalogue in the system prompt; unlike `llm-mcp-client` (which
  uses a Redis vector store + embeddings to select only relevant tools), there's no filtering —
  fine for the current 2-server setup, but won't scale to dozens of tools without context bloat.
- **No `ToolAuditLog` persistence for MCP calls** — only the agent step's observation is recorded,
  not a separate structured tool-call audit trail like `llm-mcp-client` has.
- **No streaming run endpoint** — `POST /agent/run` blocks for the entire loop. A
  `GET /agent/run/{id}/stream` (SSE) endpoint is the natural next step for long-running plans.
- **Planner JSON parsing is best-effort.** A model that doesn't follow the strict-JSON instruction
  has its raw text treated as the final answer rather than retried with a corrective prompt.
