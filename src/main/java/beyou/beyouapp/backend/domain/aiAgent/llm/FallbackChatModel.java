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
import org.springframework.ai.model.tool.ToolCallingChatOptions;

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
                ChatResponse response = provider.model().call(adaptFor(provider, prompt));
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

    /**
     * Portable ToolCallingChatOptions: the ChatClient calls getOptions() on the
     * injected model to seed the prompt options, attaches tool callbacks only when
     * the result is a ToolCallingChatOptions, and the ToolCallingAdvisor engages on
     * the same check. Per-provider specifics are re-applied in {@link #adaptFor}.
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    /**
     * Retype the prompt options to the delegate's native options class. Every
     * provider model hard-casts prompt options to its own type (DeepSeekChatOptions,
     * OpenAiChatOptions, ...), so a shared portable options object would blow up on
     * every delegate. Base = the delegate's configured defaults (model id etc.);
     * the runtime tool callbacks/context from the ChatClient are copied over.
     */
    private Prompt adaptFor(NamedChatModel provider, Prompt prompt) {
        ChatOptions incoming = prompt.getOptions();
        if (incoming == null) {
            return prompt; // delegate applies its own defaults
        }
        ChatOptions defaults = provider.model().getOptions();
        if (defaults == null) {
            return prompt;
        }
        ChatOptions.Builder<?> builder = defaults.mutate();
        if (builder instanceof ToolCallingChatOptions.Builder<?> toolBuilder
                && incoming instanceof ToolCallingChatOptions incomingTools) {
            toolBuilder.toolCallbacks(incomingTools.getToolCallbacks());
            toolBuilder.toolContext(incomingTools.getToolContext());
        }
        return prompt.mutate().chatOptions(builder.build()).build();
    }

    private Flux<ChatResponse> attempt(Prompt prompt, int index) {
        NamedChatModel provider = chain.get(index);
        if (skippedByCooldown(provider, index)) {
            return attempt(prompt, index + 1); // in-bounds: the last link never skips
        }
        AtomicBoolean emitted = new AtomicBoolean();
        return Flux.defer(() -> provider.model().stream(adaptFor(provider, prompt)))
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

    // Quota-class failures (429 rate limit, 402 payment required, 413 request too
    // large for the free tier) get the LONG cooldown: they won't heal in 30s and
    // retrying them just delays first token on every message.
    // ponytail: message sniffing — providers surface these through heterogeneous SDKs;
    // typed check covers the OpenAI SDK, string match covers DeepSeek and friends.
    private static boolean isRateLimit(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RateLimitException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            if (message.contains("429") || message.contains("402") || message.contains("413")
                    || lower.contains("rate limit") || lower.contains("payment_required")
                    || lower.contains("quota")) {
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
