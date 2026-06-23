# LLM Orchestrator

A **deep agent**: it sits *above* `llm-gateway`, `llm-rag` and `llm-mcp`, and runs a plan → act →
observe loop — with task planning, sub-agent delegation, context compaction, a scratchpad, cross-run
memory, human approval gates and live streaming — so a single prompt can answer multi-step,
long-horizon questions ("check the deployment status, summarize it, and email the on-call channel")
instead of a single LLM round-trip.

```
                         POST /orchestrator/v1/agent/run  (returns immediately, status=RUNNING)
                                       │
                                       ▼
                         ┌──────────────────────────┐
                         │    AgentLoopExecutor      │  plan → act → observe, on a background
                         │ (resumable plan/act/loop) │  virtual thread, up to agent.max-iterations
                         └────────────┬──────────────┘
                                      │ "what's the next action?" (+ task list, scratchpad listing,
                                      │  compacted transcript, session history)
                     ┌────────────────┼─────────────────────────────────────────┐
                     ▼                                                         │
          ┌─────────────────────┐                     RoutingStrategyChain dispatches
          │   llm-gateway-core  │◄────────────────────  the chosen action to:
          │   (planner LLM)     │
          └─────────────────────┘     ┌────────────┬───────────┬────────────┬───────────┬───────────────┐
                                       ▼            ▼           ▼           ▼           ▼               ▼
                                ┌──────────┐ ┌───────────┐ ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐
                                │ Gateway  │ │   RAG     │ │  MCP    │ │  Task    │ │Scratchpad│ │   SubAgent     │
                                │ LLM      │ │ Routing   │ │  Tool   │ │ Planning │ │ Routing  │ │   Routing      │
                                └──────────┘ └───────────┘ └─────────┘ └──────────┘ └──────────┘ └──────┬────────┘
                                                                                                         │ runs a nested
                                                                                                         ▼ AgentLoopExecutor
                                                                                                  (own agent_run/agent_step,
                                                                                                   same root_run_id)
                                       │
                                       ▼
                  agent_run / agent_step / agent_task / agent_artifact  (Postgres, audit trail + state)
                                       │
                                       ▼
                  RunEventBroadcaster → GET /agent/run/{id}/events (SSE: step, awaiting_approval, done)
```

Every run is persisted: `GET /agent/run/{id}` replays the full step-by-step trace (action taken,
input, observation, planner's reasoning), current task list and scratchpad file listing — for
debugging, auditing, or just polling a long-running task to completion.

---

## Tech Stack

| Concern       | Technology                                                                   |
|---------------|------------------------------------------------------------------------------|
| Language      | Java 25                                                                      |
| Framework     | Spring Boot 4.1.0, Spring MVC (+ SSE via `SseEmitter`)                       |
| AI            | Spring AI 2.0.0 — MCP client, OpenAI-compatible chat model, JDBC chat memory |
| Persistence   | Spring JDBC (`JdbcTemplate`) + PostgreSQL + Flyway                           |
| Security      | Spring Security OAuth2 Resource Server (Keycloak JWT)                        |
| Resilience    | Resilience4j (retry + circuit breaker, per downstream dependency)            |
| Observability | Actuator + Micrometer + Prometheus + OTLP tracing → Grafana Tempo            |
| API docs      | springdoc-openapi (Swagger UI)                                               |
| Build         | Maven, shared `super-pom` parent + `learning-bom` dependency management      |

No dependency or plugin version is hardcoded in this module's `pom.xml` — all versions come from
`super-pom`/`learning-bom` properties, matching the convention in `llm-gateway`, `llm-chat`,
`llm-rag` and `llm-mcp`.

---

## Design Patterns (GoF)

| Pattern                       | Where                                                            | Role                                                                                                                                      |
|-------------------------------|------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| **Strategy**                  | `RoutingStrategy` + its 6 implementations                        | Each capability (gateway LLM, RAG, MCP tools, task planning, scratchpad, sub-agent delegation) is a swappable strategy for "act"          |
| **Chain of Responsibility**   | `RoutingStrategyChain`                                           | Tries each registered `RoutingStrategy` in turn; first one whose `supports()` matches handles the step                                    |
| **Facade**                    | `GatewayClient`, `RagClient`                                     | Hide the HTTP/auth/resilience plumbing behind a one-method call (`chat()`, `retrieve()`, `generate()`)                                    |
| **Proxy (protection)**        | `ResilientToolCallbackProvider`                                  | Wraps every MCP `ToolCallback` with retry + circuit breaker; an OPEN circuit returns a structured error instead of a doomed call          |
| **Template Method (loop)**    | `AgentLoopExecutor.continueRun`                                  | Fixed plan→act→observe→repeat skeleton; the "act" step varies per `RoutingStrategy`                                                       |
| **Composite-like recursion**  | `SubAgentRoutingStrategy` → nested `AgentLoopExecutor` run       | `DELEGATE_SUBAGENT` runs a whole isolated agent loop as a single step, quarantining its intermediate context from the parent's transcript |
| **Memento**                   | `agent_run`/`agent_step`/`agent_task`/`agent_artifact` tables    | All run state is externalised so a run can be replayed (`GET /agent/run/{id}`) or *resumed* from any thread/request                       |
| **Observer**                  | `RunEventBroadcaster`                                            | Fans out `step`/`awaiting_approval`/`done` events to every SSE subscriber of a run                                                        |
| **Singleton**                 | All Spring beans                                                 | One shared, stateless instance per container                                                                                              |

---

## The Agent Loop

1. **Plan** — `AgentLoopExecutor` asks the planner LLM (a call to `llm-gateway-core`, not OpenAI
   directly) for the single next action, given the original prompt, a (possibly compacted)
   transcript, the run tree's current task list, and — for a fresh top-level run — a summary of
   recent prior runs in the same `sessionId`. The planner must respond with strict JSON:
   `{"action": "...", "toolName": "...", "input": "...", "reasoning": "..."}`.
2. **Act** — `RoutingStrategyChain` dispatches that action:
   - `GATEWAY_LLM` → a plain LLM call via `llm-gateway-core`.
   - `RAG_RETRIEVE` → grounded source chunks/citations from `llm-rag-pipeline`, no generation.
   - `RAG_GENERATE` → a full grounded answer with a faithfulness/insufficient-context check.
   - `MCP_TOOL` → invokes a named tool exposed by any configured `llm-mcp` server.
   - `PLAN_TASKS` → replaces the run tree's task list with the planner's updated snapshot.
   - `FILE_WRITE` / `FILE_READ` → writes/reads a scratchpad file, scoped to the run tree.
   - `DELEGATE_SUBAGENT` → runs a whole nested agent loop for a self-contained sub-task; only its
     final answer re-enters the parent's transcript (only offered to top-level runs — delegation
     depth is capped at 1).
   - `FINAL_ANSWER` → ends the loop immediately.
3. **Observe** — the strategy's result (or an error message, on failure) is persisted as the next
   `agent_step` and fed into the next planning turn.
4. Repeat until `FINAL_ANSWER`, the run pauses on a gated action (see **Approval Gates** below), or
   `agent.max-iterations` (default 25) is reached — at which point the last observation is returned
   as a best-effort answer and the run is marked `INCOMPLETE`.

Unparseable planner output (e.g. the model ignored the JSON instruction) is treated as a
`FINAL_ANSWER` rather than crashing the loop — the raw text becomes the answer.

### Resumability

The loop never holds run state only in memory: every planning turn rebuilds the transcript from
persisted `agent_step` rows. This is what makes `continueRun(runId)` safely callable from a
completely different request/thread than the one that started the run — which is how approval
gates, async execution and sub-agent delegation all work without any in-process "paused run"
registry.

### Context compaction

Once a run has more than `agent.compaction-trigger-steps` (default 8) persisted steps,
`ContextCompactor` folds everything except the last `agent.compaction-keep-recent-steps` (default 4)
into a rolling summary (one extra `llm-gateway-core` call, combined with any prior summary) instead
of feeding the planner an ever-growing verbatim transcript. This is what lets `agent.max-iterations`
be raised far past what a naive transcript could afford.

### Sub-agents

`DELEGATE_SUBAGENT` runs `runSubAgentToCompletion(...)` synchronously on the parent's own background
thread (safe — it's never an HTTP request thread), bounded by `agent.sub-agent-max-iterations`
(default 6). The nested run gets its own `agent_run`/`agent_step` rows (`parent_run_id` set) but
shares the parent's `root_run_id` — so a sub-agent's `FILE_WRITE`/`PLAN_TASKS` calls are visible to
the parent (and vice versa) even though its intermediate reasoning steps are not.

### Approval gates

MCP tool calls are gated **per tool**, not as a blanket "every MCP_TOOL" rule:
`agent.approval-required-mcp-tools` (default `*`) matches exact tool names or `prefix*` patterns —
narrow it to just the mutating tools (e.g. `deploy*,delete*`) once you know your catalogue. Non-MCP
actions (e.g. `RAG_GENERATE`) can additionally be gated via `agent.approval-required-actions`
(empty by default). A gated action pauses the run with status `AWAITING_APPROVAL` and a persisted
`pendingAction` instead of executing immediately.

`POST /agent/run/{id}/approve` dispatches it and resumes; `POST /agent/run/{id}/reject` instead
feeds the planner a synthetic "rejected" observation (with your reason, if given) so it can adapt
rather than the run simply dying. Both calls are race-safe — the database-level conditional update
behind `AgentRunRepository.claimPendingAction` guarantees a pending action is never dispatched
twice even if two requests race to resolve it — and both are recorded in `agent_approval_audit`
(who decided, when, and why) independently of `agent_step` so the accountability record survives
retention cleanup.

### Operational safeguards

- **Ownership.** Every run is stamped with `createdBy` (the JWT subject, or `"anonymous"` when
  `gateway-auth.enabled=false`) at creation. Every endpoint that takes a `runId` rejects callers who
  aren't that creator or `ROLE_ADMIN` with 403 — except runs with no recorded creator (rows that
  predate this check), which stay unrestricted.
- **Token budget.** `agent.max-total-tokens` (default 200,000) caps cumulative prompt+completion
  tokens across every planner/compaction call a run makes; exceeding it ends the run `INCOMPLETE`
  rather than continuing to spend.
- **Cancellation.** `POST /agent/run/{id}/cancel` stops a `RUNNING`/`AWAITING_APPROVAL` run as soon
  as its loop notices (re-checked every iteration) — idempotent, a no-op once already terminal.
- **Crash recovery.** On boot, `AgentRunRecoveryRunner` resubmits any run still `RUNNING` from a
  prior process — safe because state is always rebuilt from the database, never held only in
  memory. (Assumes a single instance against a given database.)
- **Retention.** `AgentRunRetentionJob` prunes terminal top-level runs (and their sub-trees) older
  than `agent.retention-days` (default 30) on a daily cron (`agent.retention-cron-schedule`).
- **Error containment.** Any unexpected exception inside the loop is caught and turns the run
  `FAILED` (with a generic message — details go to the server log, not the API response) instead of
  silently dying on the background thread and leaving the row stuck at `RUNNING` forever.
- **Prompt-injection framing.** Tool/RAG/sub-agent output is fenced off in the transcript
  (`<<<OBSERVATION_START>>>...<<<OBSERVATION_END>>>`) with an explicit system-prompt instruction that
  it's data to reason about, never new instructions to obey.

---

## API

### `POST /agent/run` — start a run

```bash
curl -s http://localhost:8090/orchestrator/v1/agent/run \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What is the current status of the payments-api deployment?", "sessionId": "demo-1"}'
```

Returns **immediately** (the loop runs on a background thread):

```json
{ "runId": 7, "sessionId": "demo-1", "status": "RUNNING", "steps": [], "tasks": [], "files": [] }
```

### `GET /agent/run/{runId}` — poll current state

Returns the full persisted state at any point in the run's lifecycle — steps so far, current task
list, scratchpad file listing (paths only), and `pendingAction` when `status` is
`AWAITING_APPROVAL`.

```json
{
  "runId": 7, "sessionId": "demo-1", "status": "COMPLETED",
  "finalAnswer": "payments-api is SCHEDULED for rollout at 14:00 UTC.",
  "steps": [
    { "stepIndex": 0, "action": "MCP_TOOL", "toolName": "getDeployments",
      "input": "{\"service\":\"payments-api\"}",
      "observation": "[{\"id\":42,\"status\":\"SCHEDULED\"}]",
      "reasoning": "Need the current deployment record before answering." }
  ],
  "tasks": [], "files": [],
  "parentRunId": null, "rootRunId": 7,
  "createdAt": "2026-06-22T05:40:00Z", "completedAt": "2026-06-22T05:40:04Z"
}
```

### `GET /agent/run/{runId}/events` — live progress (SSE)

`text/event-stream` of `step`, `awaiting_approval` and `done` events while connected. Falls back
naturally to polling `GET /agent/run/{id}` for durable state if you weren't connected for part of
the run.

### `POST /agent/run/{runId}/approve` / `POST /agent/run/{runId}/reject`

Only valid while `status` is `AWAITING_APPROVAL` (409 Conflict otherwise). `reject` accepts an
optional body: `{"reason": "..."}`.

### `POST /agent/run/{runId}/cancel`

Stops a `RUNNING`/`AWAITING_APPROVAL` run; idempotent (a no-op, not an error, once already
terminal).

### `GET /agent/run/{runId}/files/{path}`

Fetches one scratchpad file's full content (kept out of the main run payload since files can be
large).

All endpoints require a valid Keycloak-issued bearer token (any role in the `llm-gateway` realm —
see Security below) **and** require the caller be the run's creator or hold `ROLE_ADMIN` (403
otherwise — see Operational safeguards above). Swagger UI:
`http://localhost:8090/orchestrator/v1/swagger-ui.html`.

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

| Property                                               | Env var                                       | Default                                                        |
|--------------------------------------------------------|-----------------------------------------------|----------------------------------------------------------------|
| `server.port`                                          | `SERVER_PORT`                                 | `8090`                                                         |
| `spring.datasource.url`                                | `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` | `jdbc:postgresql://localhost:5432/spring_ai`                   |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `KEYCLOAK_ISSUER_URI`                         | `http://localhost:8081/realms/llm-gateway`                     |
| `gateway.base-url`                                     | `GATEWAY_BASE_URL`                            | `http://localhost:8080/llm/v1`                                 |
| `rag.base-url`                                         | `RAG_BASE_URL`                                | `http://localhost:8081/api/v1`                                 |
| `platform.auth.client-secret`                          | `PLATFORM_OAUTH_CLIENT_SECRET`                | `llm-orchestrator-dev-secret`                                  |
| `mcp.oauth2.client-secret`                             | `MCP_OAUTH2_CLIENT_SECRET`                    | `llm-orchestrator-secret`                                      |
| `agent.max-iterations`                                 | `AGENT_MAX_ITERATIONS`                        | `25`                                                           |
| `agent.sub-agent-max-iterations`                       | `AGENT_SUBAGENT_MAX_ITERATIONS`               | `6`                                                            |
| `agent.step-timeout-seconds`                           | `AGENT_STEP_TIMEOUT_SECONDS`                  | `30`                                                           |
| `agent.compaction-trigger-steps`                       | `AGENT_COMPACTION_TRIGGER_STEPS`              | `8`                                                            |
| `agent.compaction-keep-recent-steps`                   | `AGENT_COMPACTION_KEEP_RECENT_STEPS`          | `4`                                                            |
| `agent.approval-required-actions`                      | `AGENT_APPROVAL_REQUIRED_ACTIONS`             | (empty)                                                        |
| `agent.approval-required-mcp-tools`                    | `AGENT_APPROVAL_REQUIRED_MCP_TOOLS`           | `*`                                                            |
| `agent.max-total-tokens`                               | `AGENT_MAX_TOTAL_TOKENS`                      | `200000`                                                       |
| `agent.retention-days`                                 | `AGENT_RETENTION_DAYS`                        | `30`                                                           |
| `agent.retention-cron-schedule`                        | `AGENT_RETENTION_CRON_SCHEDULE`               | `0 0 3 * * *`                                                  |
| `agent.session-history-limit`                          | `AGENT_SESSION_HISTORY_LIMIT`                 | `3`                                                            |
| `agent.max-tasks`                                      | `AGENT_MAX_TASKS`                             | `50`                                                           |
| `agent.max-scratchpad-files`                           | `AGENT_MAX_SCRATCHPAD_FILES`                  | `20`                                                           |
| `agent.max-scratchpad-file-chars`                      | `AGENT_MAX_SCRATCHPAD_FILE_CHARS`             | `20000`                                                        |

All `agent.*` numeric properties are validated at startup (`@Validated` + `@Min`) — a misconfigured
value fails fast instead of misbehaving at runtime.

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
./mvnw test                   # unit/web-layer tests + Testcontainers Postgres repository/context tests (needs Docker)
./mvnw verify                 # adds the JaCoCo 70% instruction-coverage gate
./mvnw verify -Psecurity-scan # adds an OWASP dependency-check pass
```

Spotless (`google-java-format`) must run under JDK 21, not 25 — `JAVA_HOME=<jdk21> ./mvnw spotless:apply`.

---

## Known Limitations

- **No semantic/vector-based MCP tool selection.** Every connected MCP server's tools are exposed
  flat to the planner via a text catalogue in the system prompt; unlike `llm-mcp-client` (which
  uses a Redis vector store + embeddings to select only relevant tools), there's no filtering —
  fine for the current 2-server setup, but won't scale to dozens of tools without context bloat.
- **No `ToolAuditLog` persistence for MCP calls** — only the agent step's observation is recorded,
  not a separate structured tool-call audit trail like `llm-mcp-client` has.
- **SSE delivery is best-effort and in-memory.** `RunEventBroadcaster` only fans out to subscribers
  connected *at the moment* an event fires (no replay buffer) and doesn't survive a restart — use
  `GET /agent/run/{id}` for anything that must be durable.
- **Planner JSON parsing is best-effort.** A model that doesn't follow the strict-JSON instruction
  has its raw text treated as the final answer rather than retried with a corrective prompt.
- **Scratchpad paths are flat strings**, not a real nested filesystem — `path` is an opaque key,
  not validated against directory-traversal semantics (there's nothing to traverse: it's one table,
  not a disk).
- **Single-instance assumption.** `RunEventBroadcaster` (in-memory SSE fan-out) and
  `AgentRunRecoveryRunner` (crash-recovery sweep) both assume exactly one instance of this service
  runs against a given database; a multi-instance deployment would need a distributed lock for
  recovery and a shared pub/sub backend (e.g. Redis) for SSE to work correctly.
- **Prompt-injection framing is a mitigation, not a guarantee.** Delimiting tool/RAG output and
  instructing the planner to treat it as data reduces the odds of injected instructions being
  obeyed, but depends on the underlying model actually following that instruction — it isn't a hard
  isolation boundary.
