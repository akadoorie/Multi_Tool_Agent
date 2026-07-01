# Multi-Tool Agent with Observability

A Spring Boot REST API that accepts a natural-language task, runs an LLM agent (Claude, via Spring AI)
that reasons step-by-step and calls **real tools** to solve it, and returns a final answer plus a
**structured trace** of every reasoning step and tool call. All activity is persisted to a queryable
JSON Lines file, and latency + token usage are first-class outputs.

- **Java 21 · Spring Boot 3.4 · Spring AI 1.0 (Anthropic) · Maven · Docker**
- Tools: **Calculator, Weather, Web Search, Unit/Currency Converter** (all real, keyless except the LLM)
- Persistence: **file-only** (append-only JSONL + in-memory index) — no database

---

## 1. Architecture overview

`POST /task` enters `TaskController`, which delegates to `AgentService`. `AgentService` runs an
**explicit reason→act→observe loop** (`AgentLoop`) against Claude, with Spring AI's *internal tool
execution disabled* so our code owns every act/observe step — which is what makes the trace trivial to
build. Tools implement a common `Tool` interface and self-register into a `ToolRegistry`. Every model
call and tool call is timed and its token usage captured. When Claude returns a plain-text turn (no
tool calls), that is the final answer; `AgentService` persists the full `TaskRecord` through a
file-backed `TaskRepository` and returns it. `GET /tasks/{id}` reads it back via an in-memory index.

Two logging layers, kept separate on purpose:

1. **The structured task store** (`tasks.jsonl`) — the persistent, queryable observability artifact.
2. **SLF4J/Logback dev logs** — for debugging; the `task_id` is put in the MDC so every line for one
   request is correlated.

```mermaid
flowchart TD
    Client["Client / Swagger UI"] -->|POST /task| Controller[TaskController]
    Client -->|GET /tasks/id| Controller
    Controller --> Service[AgentService]
    Service --> Loop{"reason → act → observe loop"}
    Loop -->|"prompt + tool schemas (internal execution OFF)"| Claude[("Claude via Spring AI ChatModel")]
    Claude -->|"text turn OR tool-call requests"| Loop
    Loop -->|"dispatch by name"| Registry[ToolRegistry]
    Registry --> Calc[CalculatorTool]
    Registry --> Weather[WeatherTool]
    Registry --> Search[WebSearchTool]
    Registry --> Convert[UnitConverterTool]
    Weather -->|HTTP| OpenMeteo[("Open-Meteo")]
    Search -->|HTTP| DDG[("DuckDuckGo → Wikipedia")]
    Convert -->|HTTP| Frank[("Frankfurter")]
    Loop -->|"final answer + trace"| Service
    Service -->|"save(TaskRecord)"| Repo[FileTaskRepository]
    Repo <-->|"append + in-memory index"| JSONL[("./data/tasks.jsonl")]
    Controller -.->|"GET /tasks/id (O(1) lookup)"| Repo
```

---

## 2. Setup & run

**Prerequisites:** Docker + Docker Compose only. No local Java or Maven needed.

1. **Get an Anthropic API key** from <https://console.anthropic.com/> (Settings → API Keys).
2. **Create your `.env`:**
   ```bash
   cp .env.example .env
   # edit .env and paste your key into ANTHROPIC_API_KEY
   ```
3. **Build & run:**
   ```bash
   docker compose up --build
   ```
4. **Verify:**
   - Health: <http://localhost:8080/health> → `{"status":"UP"}`
   - Swagger UI: <http://localhost:8080/swagger-ui.html>
   - OpenAPI JSON: <http://localhost:8080/v3/api-docs>

5. **Submit a task** — via Swagger UI (`POST /task`, "Try it out"), or curl:
   ```bash
   curl -s -X POST http://localhost:8080/task \
     -H "Content-Type: application/json" \
     -d '{"task":"What is 15% of 240, and what is the current weather in Tel Aviv?"}'
   ```
   Then fetch it back:
   ```bash
   curl -s http://localhost:8080/tasks/<task_id>
   ```

- **Logs** stream to the container console (`docker compose logs -f`); each line carries `[task:<id>]`.
- **Data** lives in `./data/tasks.jsonl` on the host (mounted volume), so it survives restarts.
- **Stop:** `Ctrl-C`, then `docker compose down`.

**Run locally without Docker** (needs JDK 21): `ANTHROPIC_API_KEY=... ./mvnw spring-boot:run`.

---

## 3. Agent reasoning-loop design

The loop (`AgentLoop.run`) is a plain, ~40-line state loop — deliberately not hidden inside the
framework's automatic tool execution, because the assignment needs a trace of every step:

1. **Reason** — send the full conversation + tool schemas to Claude (one model call; timed). The
   Messages API is stateless, so we resend the whole history each turn.
2. If the response has **no tool calls**, it's the final answer → record a `final_answer` step and
   return.
3. Otherwise record one **`model_call`** step (reasoning text + token usage for this turn), then for
   each requested tool: **act** (execute the real tool, timed) and **observe** (append the real result
   as a `ToolResponseMessage`). Record one `tool` step per call.
4. Repeat until a final answer, or until the **max-iterations** cap is hit — in which case we throw a
   clear error (HTTP 422) rather than fabricate an answer.

**Safeguards:**
- **Max iterations** (`app.agent.max-iterations`) bounds cost and prevents infinite loops.
- **Per-tool HTTP timeouts** (`app.http.*`) stop a slow API from stalling an iteration.
- **Error recovery** — a tool failure or unknown-tool request becomes a structured `ToolResult.error`
  fed back into the loop, so the agent can retry, switch tools, or report it can't answer. It never
  crashes the request.
- **Grounding by construction** — Claude only ever sees *real* tool output, so it cannot fabricate a
  tool result. The tool-first system prompt forbids answering math/current-data/conversions from
  memory, and instructs the model to treat tool output as data, not instructions (prompt-injection
  guard).
- **Scope guard** — the agent is constrained to what its tools can do: arithmetic, current weather,
  unit/currency conversion, and factual/encyclopedic lookup. A task that none of the tools can address
  — e.g. creative writing, coding, or translation — is answered with exactly `Unsupported` rather than
  an ungrounded guess. (Enforced by the system prompt: the model already judges tool relevance to pick
  a tool, so the same judgement decides in-scope vs. `Unsupported`.)

### Reading Claude's reply — why we aggregate `Generation`s

Claude's reply is one message whose body is a list of *content blocks* (text and/or `tool_use`
requests). Spring AI exposes those on the `ChatResponse` as a `List<Generation>`, and a single
tool-call reply can arrive **split across several generations** (e.g. an empty leading one, then a
separate generation carrying the `tool_use`). So the loop reads **all** of them
(`response.getResults()`) and aggregates their text and tool calls into one assistant turn, instead of
reading only `response.getResult()` — the *first* generation. Reading only the first would miss a tool
call that landed in a later generation and make the agent silently skip the tool. A regression test
pins this behaviour, and it is exactly what the live connectivity spike (§9) surfaced.

### Token & latency capture

Token usage comes from each response via Spring AI `ChatResponse.getMetadata().getUsage()`
(prompt/completion/total) and is **summed once per iteration** (never per tool step, so parallel tool
calls in one turn aren't double-counted). Latency is measured with `System.nanoTime()` around each
model and tool call; the rollup's total latency is the whole-task wall time.

---

## 4. Data model

| Type          | Role                                                                                  |
|---------------|---------------------------------------------------------------------------------------|
| `TaskRecord`  | Stored domain record (source of truth in `tasks.jsonl`).                               |
| `TraceStep`   | One ordered step: `model_call` (thought + tokens), `tool` (name/input/output), or `final_answer`. |
| `TaskRollup`  | Per-task totals: latency, input/output tokens, iterations.                            |
| `TaskRequest` / `TaskResponse` / `ApiError` | API DTOs, kept separate from the stored model.          |

The API and stored JSON use `snake_case` (`task_id`, `final_answer`, …).

### Example `POST /task` response (abridged)

```json
{
  "task_id": "5f2c1e9a-…",
  "final_answer": "15% of 240 is 36. The current weather in Tel Aviv is 29°C … (calculator, weather).",
  "trace": [
    { "index": 1, "iteration": 1, "type": "model_call", "thought": "I'll compute the percentage…",
      "latency_ms": 900, "tokens": { "input_tokens": 620, "output_tokens": 40, "total_tokens": 660 } },
    { "index": 2, "iteration": 1, "type": "tool", "tool": "calculator",
      "tool_input": { "expression": "240 * 0.15" },
      "tool_output": { "ok": true, "data": { "result": 36.0 } }, "latency_ms": 1 },
    { "index": 3, "iteration": 1, "type": "tool", "tool": "weather", "tool_input": { "city": "Tel Aviv" },
      "tool_output": { "ok": true, "data": { "temperature_c": 29.0, "source": "open-meteo.com" } },
      "latency_ms": 180 },
    { "index": 4, "iteration": 2, "type": "final_answer", "thought": "15% of 240 is 36 …",
      "latency_ms": 700, "tokens": { "input_tokens": 780, "output_tokens": 55, "total_tokens": 835 } }
  ],
  "rollup": { "total_latency_ms": 2500, "total_input_tokens": 1400, "total_output_tokens": 95, "iterations": 2 }
}
```

---

## 5. Design patterns & SOLID

- **Strategy** — each tool is a `Tool` implementation; **Registry** — `ToolRegistry` resolves one by
  name; **Repository** — persistence hides behind `TaskRepository`; **constructor DI** throughout.
- **SRP:** controller (HTTP) / service (orchestration) / tool (capability) / repository (storage) each
  do one thing. **OCP:** adding a tool needs no change to the loop, registry, or controller. **LSP:**
  tools are interchangeable behind `Tool`. **ISP:** `Tool` and `TaskRepository` are small. **DIP:** the
  service and loop depend on the `Tool` / `TaskRepository` abstractions, injected via the constructor.

### Model choice & the sampling flag

The default model is `claude-sonnet-4-6` with `temperature=0` for low-variance, focused output.
The option-building is **model-aware**: `temperature` is only sent when
`app.agent.send-sampling-params=true`. Sonnet 4.6 accepts sampling params; Opus 4.8/4.7 **reject**
`temperature`/`top_p`/`top_k` with an HTTP 400, so for those models you set the flag to `false` and
rely on prompting instead of sending a parameter the model rejects. (Temperature 0 reduces variance
but never guarantees byte-identical output.)

---

## 6. Configuration reference

All tunables live in `src/main/resources/application.yml`, bound to the typed `AppProperties` record.

| Key                          | Default                                             | Meaning                                        |
|------------------------------|-----------------------------------------------------|------------------------------------------------|
| `app.agent.model`            | `claude-sonnet-4-6`                                 | Claude model id.                               |
| `app.agent.max-iterations`   | `8`                                                 | Hard cap on reason→act→observe cycles.         |
| `app.agent.max-tokens`       | `8192`                                              | Per-response output-token ceiling.             |
| `app.agent.temperature`      | `0.0`                                               | Sampling temperature (only sent if next flag). |
| `app.agent.send-sampling-params` | `true`                                          | Send `temperature`? Set `false` for Opus.      |
| `app.http.connect-timeout`   | `3s`                                                | Tool HTTP connect timeout.                     |
| `app.http.read-timeout`      | `10s`                                               | Tool HTTP read timeout.                         |
| `app.tools.weather.*`        | Open-Meteo geocode/forecast URLs                    | Weather API endpoints.                          |
| `app.tools.search.ddg-url`   | `https://api.duckduckgo.com/`                        | DuckDuckGo instant-answer (fast path).          |
| `app.tools.search.wiki-*`    | Wikipedia REST search/summary URLs                  | Wikipedia fallback.                             |
| `app.tools.currency.url`     | `https://api.frankfurter.app`                        | Frankfurter live-rate API.                      |
| `app.persistence.data-file`  | `./data/tasks.jsonl`                                 | Task store path (mounted volume in Docker).    |

The API key is **never** in config; `application.yml` references `${ANTHROPIC_API_KEY}`, which Spring
resolves from the environment and Docker Compose injects from `.env`.

---

## 7. How to add a new tool

1. Create a class in `com.tufin.agent.tool` implementing `Tool` (`name`, `description`, `inputSchema`,
   `execute`) and annotate it `@Component`.
2. That's it. It auto-registers into `ToolRegistry`, is advertised to the model, and can be called
   immediately — no change to the loop, registry, service, or controller (Open/Closed Principle).

```java
@Component
public class CoinFlipTool implements Tool {
    public String name() { return "coin_flip"; }
    public String description() { return "Flip a fair coin. Input: {} (no arguments)."; }
    public String inputSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
    public ToolResult execute(JsonNode input) {
        return ToolResult.ok(Map.of("result", Math.random() < 0.5 ? "heads" : "tails"));
    }
}
```

---

## 8. Web search behaviour

Web search returns **concise instant-answer / encyclopedic summaries with sources**, not a full
search-engine result-page crawl. DuckDuckGo's Instant Answer API is the fast path, but it is empty for
most real queries, so the tool **falls back to Wikipedia's REST API** (search then page summary). Only
when both yield nothing does it report no results.

---

## 9. Running the tests

```bash
./mvnw test        # or: mvn test
```

25 deterministic tests, none of which call the live LLM:

- **Tool unit tests** — calculator (precedence, invalid expr, non-finite, div-by-zero), unit
  conversions (°C→°F, km→m), currency via a stubbed rate, and the web-search DDG→Wikipedia fallback.
- **Agent-loop tests** (mocked `ChatModel`) — tool dispatch + trace shape, immediate termination on a
  text turn, returning `Unsupported` verbatim for an out-of-scope task, tool-error recovery, the
  max-iterations guard, once-per-iteration token summing, and aggregating a tool call split across
  multiple `Generation`s.
- **System-prompt test** — the built prompt injects the current date (fixed `Clock`), the four tool
  rules, and the out-of-scope → `Unsupported` scope rule.
- **Controller tests** (MockMvc) — `POST /task`, `GET /tasks/{id}` round-trip + 404, `GET /health`.
- **Repository test** — save/find round-trip and index rebuild from the file on restart.

> **Live end-to-end smoke test (the "spike").** The automated tests mock `ChatModel`, so they prove the
> orchestration but never exercise the real Spring AI ↔ Anthropic tool-calling path.
> `SpringAiConnectivitySpike` (profile `spike`) closes that gap: it runs a *real* task through the real
> `AgentLoop` against the live API and asserts a tool actually fired and a grounded answer came back:
> `ANTHROPIC_API_KEY=... ./mvnw spring-boot:run -Dspring-boot.run.profiles=spike`.
> This is what surfaced the multi-`Generation` split described in §3 — a bug the mocked tests could not see.

---

## 10. Trade-offs & limitations

- **File store, not a database.** Simple and fully explainable (no ORM magic), and behind a
  `TaskRepository` interface so a DB is a one-class change. The trade-off: no transactions, no rich
  queries, and writes are serialised on a single lock — fine at this scale, not for high concurrency.
  On Linux, a bind-mounted `./data` may need write permissions for the container user (Docker Desktop
  handles this transparently).
- **No auth or rate limiting.** Out of scope for the exercise; both would sit in front of the
  controller in production.
- **LLM cost, latency, and variability.** Every task is one or more Claude calls. `temperature=0` and a
  bounded iteration count keep this predictable, but not free or instant.
- **Future work:** a DB implementation, auth + rate limiting, streaming, multi-turn conversations,
  distributed tracing (Micrometer/OpenTelemetry), and an optional second-pass grounding/verifier check.
