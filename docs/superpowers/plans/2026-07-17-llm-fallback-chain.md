# LLM Fallback Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agent chat tries an ordered chain of free LLM providers (Groq → Gemini → Mistral → Cerebras) and falls back to paid DeepSeek only when all free links fail, with per-provider usage visible in Grafana.

**Architecture:** A `FallbackChatModel` decorator implements Spring AI's `ChatModel`, walking an ordered list of delegates with a before-first-emission fallback guard and an in-memory cooldown map. All free providers are OpenAI-compatible endpoints built as manual (non-bean) `OpenAiChatModel` instances; the chain is exposed as the `@Primary ChatModel` and `AiAgentService` switches its injected type from `DeepSeekChatModel` to `ChatModel`.

**Tech Stack:** Java 21, Spring Boot, Spring AI 2.0.0 (`spring-ai-starter-model-openai` + `spring-ai-starter-model-deepseek`, both already in the pom), Micrometer, Reactor, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-17-llm-fallback-chain-design.md` (read the Amendments section — it records verified Spring AI 2.0 API facts this plan relies on).

## Global Constraints

- **NO new pom dependencies.** Gemini/Mistral ride their OpenAI-compatible endpoints through the already-installed OpenAI starter.
- **Maven command is `mvn`, NOT `./mvnw`** — the Unix wrapper script is not tracked in this repo (only `mvnw.cmd`).
- **Commits require explicit user permission** (user's global rule). At every commit step: show the diff summary, ask, and only commit after a yes. Do not batch-skip these gates.
- New backend package: `beyou.beyouapp.backend.domain.aiAgent.llm` (path `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/`).
- Metric names (exact): `beyou.ai.llm.calls{provider,outcome=ok|error|skipped_cooldown}`, `beyou.ai.llm.fallback{from,reason=rate_limit|error}`, `beyou.ai.llm.exhausted`.
- Fallback fires ONLY before the first emission of a delegate. Mid-stream errors always propagate (tool/side-effect safety).
- The LAST chain member is always attempted even when in cooldown.
- Task 4 targets a DIFFERENT repo: `/home/gentek/andP/beyou/Beyou-dev-env` (its own git, its own commit gate).

---

### Task 1: `FallbackChatModel` + `NamedChatModel` (core fallback + cooldown + metrics)

**Files:**
- Create: `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/NamedChatModel.java`
- Create: `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/FallbackChatModel.java`
- Test: `src/test/java/beyou/beyouapp/backend/domain/aiAgent/llm/FallbackChatModelTest.java`

**Interfaces:**
- Consumes: Spring AI `ChatModel`, `ChatResponse`, `Prompt` (on classpath); `io.micrometer.core.instrument.MeterRegistry`; `com.openai.errors.RateLimitException` (transitive via the OpenAI starter).
- Produces (Task 2 relies on these exact signatures):
  - `public record NamedChatModel(String name, ChatModel model)`
  - `public FallbackChatModel(List<NamedChatModel> chain, Duration rateLimitCooldown, Duration errorCooldown, MeterRegistry meterRegistry, Clock clock)`
  - `public List<String> providerNames()`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/beyou/beyouapp/backend/domain/aiAgent/llm/FallbackChatModelTest.java`:

```java
package beyou.beyouapp.backend.domain.aiAgent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

class FallbackChatModelTest {

    private static final Prompt PROMPT = new Prompt("hi");
    private static final ChatResponse OK =
            new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));

    private final ChatModel first = mock(ChatModel.class);
    private final ChatModel second = mock(ChatModel.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();

    private FallbackChatModel twoLinkChain() {
        return new FallbackChatModel(
                List.of(new NamedChatModel("first", first), new NamedChatModel("second", second)),
                Duration.ofSeconds(300), Duration.ofSeconds(30), meters, clock);
    }

    @Test
    void call_firstProviderHealthy_secondNeverTouched() {
        when(first.call(PROMPT)).thenReturn(OK);

        ChatResponse response = twoLinkChain().call(PROMPT);

        assertThat(response).isSameAs(OK);
        verifyNoInteractions(second);
        assertThat(count("beyou.ai.llm.calls", "provider", "first", "outcome", "ok")).isEqualTo(1.0);
    }

    @Test
    void call_firstFails_fallsBackToSecond() {
        when(first.call(PROMPT)).thenThrow(new RuntimeException("boom"));
        when(second.call(PROMPT)).thenReturn(OK);

        ChatResponse response = twoLinkChain().call(PROMPT);

        assertThat(response).isSameAs(OK);
        assertThat(count("beyou.ai.llm.calls", "provider", "first", "outcome", "error")).isEqualTo(1.0);
        assertThat(count("beyou.ai.llm.fallback", "from", "first", "reason", "error")).isEqualTo(1.0);
    }

    @Test
    void call_allFail_lastExceptionPropagates_exhaustedCounted() {
        when(first.call(PROMPT)).thenThrow(new RuntimeException("one"));
        when(second.call(PROMPT)).thenThrow(new RuntimeException("two"));

        assertThatThrownBy(() -> twoLinkChain().call(PROMPT)).hasMessage("two");
        assertThat(meters.counter("beyou.ai.llm.exhausted").count()).isEqualTo(1.0);
    }

    @Test
    void call_rateLimitedProvider_skippedUntilCooldownExpires() {
        FallbackChatModel chain = twoLinkChain();
        when(first.call(PROMPT)).thenThrow(new RuntimeException("HTTP 429 Too Many Requests"));
        when(second.call(PROMPT)).thenReturn(OK);

        chain.call(PROMPT); // first fails with 429 -> 300s cooldown
        chain.call(PROMPT); // first must be skipped, served by second
        verify(first, times(1)).call(PROMPT);
        assertThat(count("beyou.ai.llm.calls", "provider", "first", "outcome", "skipped_cooldown"))
                .isEqualTo(1.0);
        assertThat(count("beyou.ai.llm.fallback", "from", "first", "reason", "rate_limit"))
                .isEqualTo(1.0);

        clock.advanceSeconds(301);
        reset(first);
        when(first.call(PROMPT)).thenReturn(OK);
        chain.call(PROMPT); // cooldown expired -> first serves again
        verify(first, times(1)).call(PROMPT);
    }

    @Test
    void call_lastProviderAlwaysAttempted_evenInCooldown() {
        FallbackChatModel single = new FallbackChatModel(
                List.of(new NamedChatModel("only", first)),
                Duration.ofSeconds(300), Duration.ofSeconds(30), meters, clock);
        when(first.call(PROMPT)).thenThrow(new RuntimeException("HTTP 429"));

        assertThatThrownBy(() -> single.call(PROMPT)).hasMessageContaining("429");
        // still in cooldown, but it's the last link -> attempted anyway
        assertThatThrownBy(() -> single.call(PROMPT)).hasMessageContaining("429");
        verify(first, times(2)).call(PROMPT);
    }

    @Test
    void stream_failsBeforeFirstChunk_fallsBackTransparently() {
        when(first.stream(PROMPT)).thenReturn(Flux.error(new RuntimeException("boom")));
        when(second.stream(PROMPT)).thenReturn(Flux.just(OK));

        List<ChatResponse> out = twoLinkChain().stream(PROMPT).collectList().block();

        assertThat(out).containsExactly(OK);
        assertThat(count("beyou.ai.llm.calls", "provider", "second", "outcome", "ok")).isEqualTo(1.0);
    }

    @Test
    void stream_throwsSynchronously_fallsBackTransparently() {
        // some SDKs throw on subscribe instead of returning an error Flux
        when(first.stream(PROMPT)).thenThrow(new RuntimeException("connect refused"));
        when(second.stream(PROMPT)).thenReturn(Flux.just(OK));

        List<ChatResponse> out = twoLinkChain().stream(PROMPT).collectList().block();

        assertThat(out).containsExactly(OK);
    }

    @Test
    void stream_failsMidStream_propagatesWithoutRetry() {
        when(first.stream(PROMPT)).thenReturn(
                Flux.concat(Flux.just(OK), Flux.error(new RuntimeException("mid-stream"))));

        assertThatThrownBy(() -> twoLinkChain().stream(PROMPT).collectList().block())
                .hasMessageContaining("mid-stream");
        verify(second, never()).stream(any(Prompt.class));
    }

    @Test
    void stream_allFailBeforeEmission_exhaustedCounted() {
        when(first.stream(PROMPT)).thenReturn(Flux.error(new RuntimeException("one")));
        when(second.stream(PROMPT)).thenReturn(Flux.error(new RuntimeException("two")));

        assertThatThrownBy(() -> twoLinkChain().stream(PROMPT).collectList().block())
                .hasMessageContaining("two");
        assertThat(meters.counter("beyou.ai.llm.exhausted").count()).isEqualTo(1.0);
    }

    @Test
    void providerNames_reflectChainOrder() {
        assertThat(twoLinkChain().providerNames()).containsExactly("first", "second");
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** Fixed clock the test can move forward — cooldown expiry without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-17T10:00:00Z");

        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }

        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=FallbackChatModelTest`
Expected: COMPILATION ERROR — `NamedChatModel` and `FallbackChatModel` do not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/NamedChatModel.java`:

```java
package beyou.beyouapp.backend.domain.aiAgent.llm;

import org.springframework.ai.chat.model.ChatModel;

/** A chain link: provider name (metrics/log label) + the model that serves it. */
public record NamedChatModel(String name, ChatModel model) {
}
```

Create `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/FallbackChatModel.java`:

```java
package beyou.beyouapp.backend.domain.aiAgent.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.openai.errors.RateLimitException;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * ChatModel decorator that walks an ordered chain of providers.
 *
 * Fallback fires ONLY while a provider has emitted nothing: a call() that
 * throws, or a stream() that errors before its first chunk. Once anything was
 * emitted the error propagates unchanged — a turn is never replayed on another
 * provider. (Spring AI 2.0 runs tools in the ChatClient's ToolCallingAdvisor
 * ABOVE this class, so each tool round is its own call/stream and tool
 * side-effects can never be duplicated by a fallback.)
 *
 * A failing provider enters a cooldown window and is skipped while it lasts
 * (rate limit -> long window, anything else -> short). The last provider in
 * the chain is always attempted, cooldown or not, so there is always a real
 * response or a real exception — never an empty chain.
 */
@Slf4j
public class FallbackChatModel implements ChatModel {

    private final List<NamedChatModel> chain;
    private final Duration rateLimitCooldown;
    private final Duration errorCooldown;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ConcurrentMap<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    public FallbackChatModel(List<NamedChatModel> chain, Duration rateLimitCooldown,
            Duration errorCooldown, MeterRegistry meterRegistry, Clock clock) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("LLM chain needs at least one model");
        }
        this.chain = List.copyOf(chain);
        this.rateLimitCooldown = rateLimitCooldown;
        this.errorCooldown = errorCooldown;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** Provider names in chain order (boot log + tests). */
    public List<String> providerNames() {
        return chain.stream().map(NamedChatModel::name).toList();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException last = null;
        for (int i = 0; i < chain.size(); i++) {
            NamedChatModel provider = chain.get(i);
            if (skippedByCooldown(provider, i)) {
                continue;
            }
            try {
                ChatResponse response = provider.model().call(prompt);
                count(provider.name(), "ok");
                return response;
            } catch (RuntimeException e) {
                last = e;
                failAndCooldown(provider, e, i < chain.size() - 1);
            }
        }
        meterRegistry.counter("beyou.ai.llm.exhausted").increment();
        throw last; // never null: the last link is always attempted
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return attempt(prompt, 0);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return chain.get(0).model().getDefaultOptions();
    }

    private Flux<ChatResponse> attempt(Prompt prompt, int index) {
        NamedChatModel provider = chain.get(index);
        if (skippedByCooldown(provider, index)) {
            return attempt(prompt, index + 1); // in-bounds: the last link never skips
        }
        AtomicBoolean emitted = new AtomicBoolean();
        return Flux.defer(() -> provider.model().stream(prompt))
                .doOnNext(chunk -> emitted.set(true))
                .doOnComplete(() -> count(provider.name(), "ok"))
                .onErrorResume(e -> {
                    boolean isLast = index == chain.size() - 1;
                    boolean willFallback = !emitted.get() && !isLast;
                    failAndCooldown(provider, e, willFallback);
                    if (emitted.get()) {
                        // Mid-stream: tokens already reached the client and tool
                        // side-effects may exist upstream — never replay.
                        return Flux.error(e);
                    }
                    if (isLast) {
                        meterRegistry.counter("beyou.ai.llm.exhausted").increment();
                        return Flux.error(e);
                    }
                    return attempt(prompt, index + 1);
                });
    }

    /** True (and counted) when the provider should be skipped. Last link never skips. */
    private boolean skippedByCooldown(NamedChatModel provider, int index) {
        if (index == chain.size() - 1) {
            return false;
        }
        Instant until = cooldownUntil.get(provider.name());
        if (until == null || clock.instant().isAfter(until)) {
            return false;
        }
        count(provider.name(), "skipped_cooldown");
        return true;
    }

    private void failAndCooldown(NamedChatModel provider, Throwable e, boolean willFallback) {
        boolean rateLimit = isRateLimit(e);
        cooldownUntil.put(provider.name(),
                clock.instant().plus(rateLimit ? rateLimitCooldown : errorCooldown));
        count(provider.name(), "error");
        if (willFallback) {
            meterRegistry.counter("beyou.ai.llm.fallback",
                    "from", provider.name(),
                    "reason", rateLimit ? "rate_limit" : "error").increment();
            log.warn("LLM provider '{}' failed ({}), falling back to next in chain",
                    provider.name(), e.toString());
        } else {
            log.error("LLM provider '{}' failed with no fallback (mid-stream or end of chain): {}",
                    provider.name(), e.toString());
        }
    }

    // ponytail: message sniffing — providers surface 429 through heterogeneous SDKs;
    // typed check covers the OpenAI SDK, string match covers DeepSeek and friends.
    private static boolean isRateLimit(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RateLimitException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && (message.contains("429")
                    || message.toLowerCase().contains("rate limit"))) {
                return true;
            }
        }
        return false;
    }

    private void count(String provider, String outcome) {
        meterRegistry.counter("beyou.ai.llm.calls",
                "provider", provider, "outcome", outcome).increment();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=FallbackChatModelTest`
Expected: 10 tests, all PASS.

- [ ] **Step 5: Commit (ASK USER FIRST — global rule)**

```bash
git add src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/ \
        src/test/java/beyou/beyouapp/backend/domain/aiAgent/llm/FallbackChatModelTest.java
git commit -m "feat(agent): FallbackChatModel — ordered LLM chain with cooldown + metrics"
```

---

### Task 2: `LlmChainProperties` + `LlmChainConfig` (chain assembly from config)

**Files:**
- Create: `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainProperties.java`
- Create: `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainConfig.java`
- Modify: `src/main/resources/application.yaml` (after the `spring.ai` block, alongside the existing top-level `ai:` section at ~line 112)
- Modify: `envExample` (append new variables)
- Test: `src/test/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainConfigTest.java`

**Interfaces:**
- Consumes: `NamedChatModel`, `FallbackChatModel` from Task 1 (exact constructor above); auto-configured beans `DeepSeekChatModel`, `ObservationRegistry`, `MeterRegistry`.
- Produces: `@Primary ChatModel` bean named `agentChatModel` (Task 3 injects plain `ChatModel`); record `LlmChainProperties(List<String> order, int cooldownRateLimitSeconds, int cooldownErrorSeconds, Map<String, Provider> providers)` with nested `record Provider(String baseUrl, String apiKey, String model)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainConfigTest.java`:

```java
package beyou.beyouapp.backend.domain.aiAgent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

class LlmChainConfigTest {

    private final DeepSeekChatModel deepSeek = mock(DeepSeekChatModel.class);

    private ChatModel build(LlmChainProperties props) {
        return new LlmChainConfig().agentChatModel(
                props, deepSeek, ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    @Test
    void chainFollowsConfiguredOrder_andSkipsProvidersWithoutApiKey() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("groq", "gemini", "deepseek"), 300, 30,
                Map.of(
                        "groq", new LlmChainProperties.Provider(
                                "https://api.groq.com/openai/v1", "gk-123", "llama-3.3-70b-versatile"),
                        "gemini", new LlmChainProperties.Provider(
                                "https://generativelanguage.googleapis.com/v1beta/openai", "", "gemini-2.5-flash")));

        ChatModel chain = build(props);

        assertThat(chain).isInstanceOf(FallbackChatModel.class);
        assertThat(((FallbackChatModel) chain).providerNames()).containsExactly("groq", "deepseek");
    }

    @Test
    void unknownProviderNameInOrder_isSkippedNotFatal() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("typo-provider", "deepseek"), 300, 30, Map.of());

        assertThat(((FallbackChatModel) build(props)).providerNames()).containsExactly("deepseek");
    }

    @Test
    void deepseekOnly_whenNoFreeProviderConfigured() {
        LlmChainProperties props = new LlmChainProperties(
                List.of("groq", "gemini", "mistral", "cerebras", "deepseek"), 300, 30, Map.of());

        assertThat(((FallbackChatModel) build(props)).providerNames()).containsExactly("deepseek");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=LlmChainConfigTest`
Expected: COMPILATION ERROR — `LlmChainProperties` and `LlmChainConfig` do not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainProperties.java`:

```java
package beyou.beyouapp.backend.domain.aiAgent.llm;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fallback chain config. {@code order} lists chain links first-to-last;
 * "deepseek" is the native starter bean, every other name must have an
 * entry in {@code providers} (OpenAI-compatible endpoint).
 */
@ConfigurationProperties(prefix = "ai.llm-chain")
public record LlmChainProperties(
        List<String> order,
        int cooldownRateLimitSeconds,
        int cooldownErrorSeconds,
        Map<String, Provider> providers) {

    public LlmChainProperties {
        order = order == null ? List.of("deepseek") : order;
        providers = providers == null ? Map.of() : providers;
    }

    public record Provider(String baseUrl, String apiKey, String model) {
    }
}
```

Create `src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainConfig.java`:

```java
package beyou.beyouapp.backend.domain.aiAgent.llm;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles the agent's LLM fallback chain. Free providers are manual
 * OpenAiChatModel instances (NOT beans — keeps the auto-configured
 * OpenAiChatModel unambiguous for SpringAiRoutineDraftGenerator); DeepSeek
 * is the existing starter bean. Providers without an API key are skipped,
 * so dev/e2e boot with a DeepSeek-only chain and zero new env vars.
 */
@Configuration
@EnableConfigurationProperties(LlmChainProperties.class)
@Slf4j
public class LlmChainConfig {

    @Bean
    @Primary
    ChatModel agentChatModel(LlmChainProperties props, DeepSeekChatModel deepSeek,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        List<NamedChatModel> chain = new ArrayList<>();
        for (String name : props.order()) {
            if ("deepseek".equals(name)) {
                chain.add(new NamedChatModel("deepseek", deepSeek));
                continue;
            }
            LlmChainProperties.Provider provider = props.providers().get(name);
            if (provider == null || provider.apiKey() == null || provider.apiKey().isBlank()) {
                log.warn("LLM chain: provider '{}' not configured (missing entry or API key) — skipping", name);
                continue;
            }
            chain.add(new NamedChatModel(name,
                    openAiCompatible(provider, observationRegistry, meterRegistry)));
        }
        FallbackChatModel model = new FallbackChatModel(chain,
                Duration.ofSeconds(props.cooldownRateLimitSeconds()),
                Duration.ofSeconds(props.cooldownErrorSeconds()),
                meterRegistry, Clock.systemUTC());
        log.info("Agent LLM chain: {}", model.providerNames());
        return model;
    }

    private static ChatModel openAiCompatible(LlmChainProperties.Provider provider,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.apiKey())
                .maxRetries(0) // fail fast: the chain IS the retry
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .options(OpenAiChatOptions.builder().model(provider.model()).build())
                // manual builds don't get observability wired for free — without
                // these two, the provider vanishes from the gen_ai_* Grafana panels
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .build();
    }
}
```

- [ ] **Step 4: Add configuration to `application.yaml`**

In `src/main/resources/application.yaml`, extend the existing top-level `ai:` block (currently holding `ai.routine.enabled`, ~line 112):

```yaml
ai:
  routine:
    enabled: ${AI_ROUTINE_ENABLED:true}
  llm-chain:
    order: ${LLM_CHAIN_ORDER:groq,gemini,mistral,cerebras,deepseek}
    cooldown-rate-limit-seconds: ${LLM_COOLDOWN_RATELIMIT:300}
    cooldown-error-seconds: ${LLM_COOLDOWN_ERROR:30}
    providers:
      groq:
        base-url: https://api.groq.com/openai/v1
        api-key: ${GROQ_API_KEY:}
        model: ${GROQ_MODEL:llama-3.3-70b-versatile}
      gemini:
        base-url: https://generativelanguage.googleapis.com/v1beta/openai
        api-key: ${GEMINI_API_KEY:}
        model: ${GEMINI_MODEL:gemini-2.5-flash}
      mistral:
        base-url: https://api.mistral.ai/v1
        api-key: ${MISTRAL_API_KEY:}
        model: ${MISTRAL_MODEL:mistral-small-latest}
      cerebras:
        base-url: https://api.cerebras.ai/v1
        api-key: ${CEREBRAS_API_KEY:}
        model: ${CEREBRAS_MODEL:llama-3.3-70b}
```

NOTE for the implementer: verify the current free-tier, tool-capable model ids on each
provider's docs at implementation time and adjust the defaults if one is retired
(these were correct as of 2026-07; only the yaml defaults change, nothing in code).

- [ ] **Step 5: Append the new variables to `envExample`**

```bash
# --- LLM fallback chain (agent chat). Providers without a key are skipped. ---
LLM_CHAIN_ORDER=groq,gemini,mistral,cerebras,deepseek
GROQ_API_KEY=
GEMINI_API_KEY=
MISTRAL_API_KEY=
CEREBRAS_API_KEY=
# Optional model overrides: GROQ_MODEL, GEMINI_MODEL, MISTRAL_MODEL, CEREBRAS_MODEL
# Optional cooldowns (seconds): LLM_COOLDOWN_RATELIMIT=300, LLM_COOLDOWN_ERROR=30
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=LlmChainConfigTest`
Expected: 3 tests PASS.

- [ ] **Step 7: Commit (ASK USER FIRST — global rule)**

```bash
git add src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainProperties.java \
        src/main/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainConfig.java \
        src/test/java/beyou/beyouapp/backend/domain/aiAgent/llm/LlmChainConfigTest.java \
        src/main/resources/application.yaml envExample
git commit -m "feat(agent): assemble LLM fallback chain from config as primary ChatModel"
```

---

### Task 3: Switch `AiAgentService` to the chain + full regression

**Files:**
- Modify: `src/main/java/beyou/beyouapp/backend/domain/aiAgent/AiAgentService.java:21` (import) and `:74` (constructor parameter)
- Modify: `CLAUDE.md` (backend repo — AI agent section, one paragraph)

**Interfaces:**
- Consumes: the `@Primary ChatModel` bean from Task 2.
- Produces: nothing new — the service's public API is unchanged.

- [ ] **Step 1: Change the injected type**

In `AiAgentService.java` replace the import
`org.springframework.ai.deepseek.DeepSeekChatModel` with
`org.springframework.ai.chat.model.ChatModel`, and change the constructor signature:

```java
public AiAgentService(ChatModel chatModel,
        ChatMemory chatMemory,
        ChatService chatService,
        AgentMessageService agentMessageService,
        Tools tools,
        MeterRegistry meterRegistry,
        @Value("classpath:/prompts/aiAgent.st") Resource systemTemplate) {
```

Nothing else in the file changes (`ChatClient.builder(chatModel)` accepts any `ChatModel`).

- [ ] **Step 2: Run the full test suite**

Run: `mvn test`
Expected: ALL tests pass (in particular `AiAgentControllerTest` — the context now wires
`FallbackChatModel` as primary; no test constructs `AiAgentService` with a concrete
`DeepSeekChatModel`, verified before planning).

Gotcha from memory: if `target/surefire-reports` is root-owned from an old docker run,
`sudo rm -rf target` first.

- [ ] **Step 3: Document in the backend `CLAUDE.md`**

Add under the Architecture/agent notes (near the AI agent description):

```markdown
- **LLM fallback chain** (2026-07-17, branch `llm_fallback`): the agent chat's
  `ChatModel` is a `FallbackChatModel` (`domain/aiAgent/llm/`) walking
  `ai.llm-chain.order` (default groq→gemini→mistral→cerebras→deepseek). Free
  providers are OpenAI-compatible manual `OpenAiChatModel`s (no extra starters);
  providers without an API key are skipped at boot. Fallback only before the
  first emission (tools never re-run); 429 → 300s cooldown, other errors → 30s;
  the last link always plays. Metrics: `beyou.ai.llm.calls/fallback/exhausted`.
  Routine generation stays pinned to OpenAI, outside the chain.
```

- [ ] **Step 4: Boot smoke test**

Run: `mvn spring-boot:run` (with your normal dev env), watch for the boot log line
`Agent LLM chain: [deepseek]` (or the full list if keys are set), then Ctrl-C.
Send one chat message through the UI/API if the dev stack is up.

- [ ] **Step 5: Commit (ASK USER FIRST — global rule)**

```bash
git add src/main/java/beyou/beyouapp/backend/domain/aiAgent/AiAgentService.java CLAUDE.md
git commit -m "feat(agent): route agent chat through the LLM fallback chain"
```

---

### Task 4: Grafana — "Fallback chain" row (repo `Beyou-dev-env`)

**Files:**
- Modify: `/home/gentek/andP/beyou/Beyou-dev-env/monitoring/grafana/dashboards/beyou-ai-agent.json`

**Interfaces:**
- Consumes: Prometheus metrics produced by Task 1 (`beyou_ai_llm_calls_total`, `beyou_ai_llm_fallback_total`, `beyou_ai_llm_exhausted_total` — Micrometer's Prometheus export appends `_total` to counters and converts dots to underscores).
- Produces: a new dashboard row appended after the existing 26 panels (current max id 26, grid ends at y=47).

- [ ] **Step 1: Append the new row + 4 panels**

The file is hand-authored JSON (the `.gen_dashboard.py` script only generates
`beyou-service-health.json` — do NOT run it against this file). Append these objects to
the `panels` array (ids 27-31, y starts at 47; match the datasource shape used by the
existing panels — `"datasource": {"type": "prometheus", "uid": "prometheus"}`):

```json
{
  "collapsed": false, "gridPos": {"h": 1, "w": 24, "x": 0, "y": 47},
  "id": 27, "panels": [], "title": "Fallback chain — who is actually serving?", "type": "row"
},
{
  "datasource": {"type": "prometheus", "uid": "prometheus"},
  "gridPos": {"h": 8, "w": 12, "x": 0, "y": 48}, "id": 28,
  "title": "Serving rate by provider",
  "type": "timeseries",
  "fieldConfig": {"defaults": {"unit": "reqps"}, "overrides": []},
  "targets": [{
    "datasource": {"type": "prometheus", "uid": "prometheus"},
    "expr": "sum by (provider) (rate(beyou_ai_llm_calls_total{application=~\"$application\",outcome=\"ok\"}[5m]))",
    "legendFormat": "{{provider}}", "refId": "A"
  }]
},
{
  "datasource": {"type": "prometheus", "uid": "prometheus"},
  "gridPos": {"h": 8, "w": 12, "x": 12, "y": 48}, "id": 29,
  "title": "Calls by provider/outcome (24h)",
  "type": "table",
  "fieldConfig": {"defaults": {}, "overrides": []},
  "targets": [{
    "datasource": {"type": "prometheus", "uid": "prometheus"},
    "expr": "sum by (provider, outcome) (increase(beyou_ai_llm_calls_total{application=~\"$application\"}[24h]))",
    "format": "table", "instant": true, "refId": "A"
  }]
},
{
  "datasource": {"type": "prometheus", "uid": "prometheus"},
  "gridPos": {"h": 5, "w": 6, "x": 0, "y": 56}, "id": 30,
  "title": "Fallback hops (1h)",
  "type": "stat",
  "fieldConfig": {"defaults": {"unit": "short"}, "overrides": []},
  "targets": [{
    "datasource": {"type": "prometheus", "uid": "prometheus"},
    "expr": "sum(increase(beyou_ai_llm_fallback_total{application=~\"$application\"}[1h])) or vector(0)",
    "refId": "A"
  }]
},
{
  "datasource": {"type": "prometheus", "uid": "prometheus"},
  "gridPos": {"h": 5, "w": 6, "x": 6, "y": 56}, "id": 31,
  "title": "Chain exhausted (24h)",
  "type": "stat",
  "fieldConfig": {"defaults": {"unit": "short", "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": null}, {"color": "red", "value": 1}]}}, "overrides": []},
  "targets": [{
    "datasource": {"type": "prometheus", "uid": "prometheus"},
    "expr": "sum(increase(beyou_ai_llm_exhausted_total{application=~\"$application\"}[24h])) or vector(0)",
    "refId": "A"
  }]
}
```

Before saving, mirror the exact `fieldConfig`/`options` conventions of the neighboring
panels of the same type in that file (copy an existing `stat`/`timeseries`/`table`
panel and swap title/query/gridPos/id) so the dashboard stays visually consistent.

- [ ] **Step 2: Validate the JSON**

Run:
```bash
python3 -c "import json; d=json.load(open('/home/gentek/andP/beyou/Beyou-dev-env/monitoring/grafana/dashboards/beyou-ai-agent.json')); print(len(d['panels']), 'panels, ids unique:', len({p['id'] for p in d['panels']})==len(d['panels']))"
```
Expected: `31 panels, ids unique: True`

- [ ] **Step 3: Visual check (if the dev stack is running)**

Reload Grafana provisioning (restart the grafana container or wait for the provisioning
refresh) and confirm the new row renders. With no chain traffic yet, panels show 0 —
that's the expected empty state.

- [ ] **Step 4: Commit in Beyou-dev-env (ASK USER FIRST — global rule)**

```bash
cd /home/gentek/andP/beyou/Beyou-dev-env
git add monitoring/grafana/dashboards/beyou-ai-agent.json
git commit -m "feat(grafana): fallback-chain row on the AI agent dashboard"
```

---

## Self-Review (done at plan time)

- **Spec coverage:** FallbackChatModel + emission guard (Task 1), cooldown two-tier (Task 1), chain assembly + skip-without-key + @Primary + ObservationRegistry wiring (Task 2), yaml/env config (Task 2), one-line service change (Task 3), CLAUDE.md (Task 3), metrics + Grafana row (Tasks 1/4). Amendments section of the spec reflects the no-new-deps decision. ✔
- **Placeholder scan:** the single deliberate soft spot is default model ids in yaml (flagged with an explicit implementer note — provider lineups rotate; verify at implementation). No TBDs elsewhere. ✔
- **Type consistency:** `NamedChatModel(String, ChatModel)`, `FallbackChatModel(List, Duration, Duration, MeterRegistry, Clock)`, `providerNames()`, `LlmChainProperties(List, int, int, Map)` used identically across Tasks 1-3. Metric names identical across Task 1 code, tests, and Task 4 queries (dots → underscores + `_total` in PromQL). ✔
