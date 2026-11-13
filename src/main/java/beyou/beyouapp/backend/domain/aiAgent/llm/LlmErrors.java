package beyou.beyouapp.backend.domain.aiAgent.llm;

import com.openai.errors.RateLimitException;

/** What a failed LLM call means, for the callers that tell the user about it. */
public final class LlmErrors {

    private LlmErrors() {
    }

    /**
     * True for a quota-class refusal: 429 rate limit, 402 payment required, or 413 when the
     * request is too large for the tier that received it.
     *
     * <p>The agent stream uses this to say "the assistant is out of quota, wait a bit" when
     * that is what happened, and keep "the assistant broke" for everything else.
     *
     * <p>The typed check covers what the OpenAI SDK raises itself. The message check stays
     * because the gateway relays refusals from the providers behind it, and each of them
     * words a quota refusal its own way. Keep this the only place that reads those words.
     */
    public static boolean isRateLimit(Throwable e) {
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
}
