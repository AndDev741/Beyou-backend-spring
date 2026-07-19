# LLM Fallback Chain — Design

**Date:** 2026-07-17
**Branch:** `llm_fallback`
**Status:** Approved (pending user review of this doc)

## Problem

The agent chat runs on the paid DeepSeek API. For a free open-source app, LLM cost must
be ~zero. Free-tier providers (Groq, Gemini, Mistral, Cerebras) are individually
unreliable (rate limits, outages), but a chain of them is resilient. OpenRouter was
rejected: its free-model rate limit is per API key, so a single shared key would be
exhausted immediately.

## Goal

User sends a chat message → system tries free model #1 → on failure, transparently
falls back to the next free model → paid DeepSeek only as the final link. Per-model
usage must be visible in Grafana.

## Decisions made

| Question | Decision |
|---|---|
| Providers | Multiple direct providers: Groq, Gemini, Mistral, Cerebras (+ DeepSeek paid as last resort). All must support tool-calling. |
| Mid-stream failure | Fallback ONLY before the first emission from the delegate's Flux. Any emitted chunk (including tool-call chunks) disables fallback for that turn — tools never run twice. Mid-stream failure keeps today's behavior (persist partial + error event). |
| Resilience library | None. Plain `ConcurrentHashMap` cooldown (~15 lines). resilience4j is not in the pom and not worth a new dependency. |
| Routine generation | Out of scope. `SpringAiRoutineDraftGenerator` keeps its own OpenAI model. Chain can be reused there later. |

## Architecture

New package: `domain/aiAgent/llm/`.

### `FallbackChatModel implements ChatModel` (~80 lines)

Holds an ordered `List<NamedChatModel>` (record: `String name`, `ChatModel delegate`).

- **`call(prompt)`** — iterate the chain, skipping providers whose cooldown hasn't
  expired. Exception → register cooldown → next provider. All failed/skipped → rethrow
  the last exception (existing controller/SSE error handling takes over).
- **`stream(prompt)`** — recursive attempt with an emission guard:

  ```java
  Flux<ChatResponse> attempt(int i) {
      AtomicBoolean emitted = new AtomicBoolean();
      return delegate(i).stream(prompt)
          .doOnNext(r -> emitted.set(true))
          .onErrorResume(e -> emitted.get() || isLast(i)
              ? Flux.error(e)
              : cooldownAndTry(i + 1, e));
  }
  ```

  Rationale: in Spring AI the internal tool loop runs inside the ChatModel, and
  tool-call chunks count as emissions — the guard is naturally conservative.

### Cooldown (field inside `FallbackChatModel`, no separate class)

`ConcurrentHashMap<String, Instant> cooldownUntil`.

- Rate-limit error (HTTP 429 / provider-specific equivalent) → long cooldown
  (default 300s).
- Any other error → short cooldown (default 30s) so a dead provider isn't hammered
  on every message.
- Expiry is a simple `Instant.now().isAfter(...)` check — nothing to clean up.

### `LlmChainConfig` (`@Configuration`)

- Builds delegates:
  - **Groq, Cerebras** — manual `OpenAiChatModel` beans (OpenAI-compatible,
    different `base-url` + key). **Must be wired with the `ObservationRegistry`**
    so Spring AI's `gen_ai_*` metrics are emitted (auto-configured models get this
    for free; manual builds do not).
  - **Gemini, Mistral** — their own Spring AI starters (2 new pom dependencies).
  - **DeepSeek** — the existing auto-configured bean, as the final link.
- Exposes the chain as `@Primary ChatModel`.
- Providers with no API key configured are excluded from the chain at boot with a
  WARN log — dev/e2e keep working with DeepSeek only.
- ⚠️ The extra `OpenAiChatModel` beans need `@Qualifier`s so they don't collide with
  the auto-configured one used by `SpringAiRoutineDraftGenerator`.

### `AiAgentService` — one-line change

Constructor parameter `DeepSeekChatModel chatModel` → `ChatModel chatModel`
(resolved to the `@Primary` chain). ChatClient, memory advisor, tools, SSE plumbing:
untouched.

## Configuration (`application.yaml`)

```yaml
ai:
  llm-chain:
    order: ${LLM_CHAIN_ORDER:groq,gemini,mistral,cerebras,deepseek}
    cooldown-rate-limit-seconds: ${LLM_COOLDOWN_RATELIMIT:300}
    cooldown-error-seconds: ${LLM_COOLDOWN_ERROR:30}
    groq:
      api-key: ${GROQ_API_KEY:}
      model: ${GROQ_MODEL:llama-3.3-70b-versatile}
    gemini:
      api-key: ${GEMINI_API_KEY:}
      model: ${GEMINI_MODEL:gemini-2.5-flash}
    mistral:
      api-key: ${MISTRAL_API_KEY:}
      model: ${MISTRAL_MODEL:mistral-small-latest}
    cerebras:
      api-key: ${CEREBRAS_API_KEY:}
      model: ${CEREBRAS_MODEL:llama-3.3-70b}
```

`envExample` gains the new variables. Exact default model names are confirmed at
implementation time against each provider's current free-tier, tool-capable lineup.

## Observability

### Already covered for free (verified against the live dashboard)

`Beyou-dev-env/monitoring/grafana/dashboards/beyou-ai-agent.json` already queries
Spring AI's built-in metrics grouped by `gen_ai_system` and `gen_ai_request_model`:

- "Model call rate by provider" → will fan out per provider automatically
- "Avg model call duration", "Model call errors" → same
- "Tokens by model (24h)" → per-model token spend, automatic

Precondition: every delegate emits `gen_ai_*` metrics — hence the
`ObservationRegistry` wiring requirement above.

### New: fallback-specific metrics

One counter, tagged, via the existing `MeterRegistry`:

- `beyou.ai.llm.calls{provider, outcome}` where outcome ∈
  `ok | error | skipped_cooldown`
- `beyou.ai.llm.fallback{from, to}` — incremented on each hop
- `beyou.ai.llm.exhausted` — whole chain failed

### Dashboard extension (`Beyou-dev-env`, separate PR)

New row **"Fallback chain — who is actually serving?"** in `beyou-ai-agent.json`:

1. **Timeseries** — serving rate by provider:
   `sum by (provider) (rate(beyou_ai_llm_calls_total{outcome="ok"}[5m]))`
2. **Stat** — fallback hops (1h): `sum(increase(beyou_ai_llm_fallback_total[1h]))`
3. **Stat** — chain exhausted (24h): `sum(increase(beyou_ai_llm_exhausted_total[24h]))`
4. **Table** — calls by provider/outcome (24h):
   `sum by (provider, outcome) (increase(beyou_ai_llm_calls_total[24h]))`

## Error handling summary

| Failure | Behavior |
|---|---|
| Provider fails before first emission | Cooldown registered, next provider, user never notices |
| Provider fails mid-stream | No retry. Persist partial turn + SSE error event (today's behavior) |
| Provider returns 429 | Long cooldown (300s default), skipped on subsequent calls until expiry |
| All providers fail/skipped | Last exception rethrown → existing error pipeline (SSE `error` event / HTTP error) |
| Provider has no API key | Excluded from chain at boot, WARN log |

## Testing

- **`FallbackChatModelTest`** (unit, no Spring context):
  - delegate 1 throws → delegate 2 answers (call + stream)
  - stream fails after first emission → error propagates, no retry
  - 429 → provider skipped on next call; skipped provider is retried after cooldown expiry
  - all delegates fail → last exception rethrown
  - metrics counters incremented with the right tags (SimpleMeterRegistry)
- **`LlmChainConfigTest`**: chain respects `order`; provider without key excluded.
- Existing `AiAgentService` tests unchanged (injected type is still a ChatModel).

## Amendments (2026-07-17, pre-implementation API verification)

Verified against the Spring AI 2.0.0 jars in the local repo before planning:

1. **No new pom dependencies.** Gemini and Mistral expose OpenAI-compatible endpoints
   (`https://generativelanguage.googleapis.com/v1beta/openai`, `https://api.mistral.ai/v1`),
   so ALL four free providers are manual `OpenAiChatModel` instances differing only in
   `base-url` + key + model. The Gemini/Mistral starters are dropped from the design.
   The provider instances are NOT Spring beans (plain objects assembled inside the chain
   bean factory), so `SpringAiRoutineDraftGenerator`'s `OpenAiChatModel` injection stays
   unambiguous — no `@Qualifier` needed anywhere.
2. **Tool loop location.** In Spring AI 2.0 tool calling moved out of the ChatModel into
   the ChatClient's `ToolCallingAdvisor`. Each tool round is a separate
   `call()`/`stream()` on the fallback model; a fallback between rounds does NOT re-run
   tools (results are held by the advisor). The emission guard stays as designed.
3. **SDK detail.** Spring AI 2.0's `OpenAiChatModel` wraps the official
   `com.openai:openai-java` SDK: `OpenAIOkHttpClient.builder().baseUrl(...).apiKey(...)
   .maxRetries(0).build()` → `OpenAiChatModel.builder().openAiClient(...)`.
   `maxRetries(0)` because the chain IS the retry (SDK default of 2 would waste seconds
   re-hitting an exhausted free tier before falling back).
4. **Metrics label caveat.** All OpenAI-compatible delegates report
   `gen_ai_system="openai"`, so existing per-provider panels distinguish models via
   `gen_ai_request_model`. The new `beyou.ai.llm.calls{provider=...}` counter is the
   authoritative per-provider view. Fallback counter tags are `{from, reason}` (not
   `{from, to}` — "to" is unknowable at increment time when the next provider may be
   skipped by cooldown).
5. **Last-link rule.** The final chain member is always attempted even if in cooldown,
   guaranteeing a real exception to propagate when everything is down.

## Out of scope (deliberate)

- Whole-turn retry on another model (needs idempotent tools)
- Active provider health checks
- Chain for routine generation (pluggable later, same bean)
- Persistence of cooldown state (in-memory only; restarts reset it — fine)
