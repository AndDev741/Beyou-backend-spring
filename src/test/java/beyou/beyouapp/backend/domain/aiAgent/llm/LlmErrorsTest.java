package beyou.beyouapp.backend.domain.aiAgent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmErrorsTest {

    /**
     * The agent stream asks this to choose between "the assistant is out of quota, wait a
     * bit" and "the assistant broke". Before that, the stream reported the Java exception's
     * class name, which matched no translation and rendered as a generic error, so a quota
     * refusal looked exactly like a crash.
     *
     * The gateway relays refusals from the providers behind it, and they word a quota
     * refusal differently. Each row below is a shape one of them has sent.
     */
    @Test
    void isRateLimit_recognisesEveryShapeAQuotaRefusalArrivesIn() {
        assertThat(LlmErrors.isRateLimit(new RuntimeException("429 Too Many Requests"))).isTrue();
        assertThat(LlmErrors.isRateLimit(new RuntimeException("402 payment_required"))).isTrue();
        assertThat(LlmErrors.isRateLimit(new RuntimeException("413 body too large"))).isTrue();
        assertThat(LlmErrors.isRateLimit(new RuntimeException("Rate limit reached"))).isTrue();
        assertThat(LlmErrors.isRateLimit(new RuntimeException("daily quota exceeded"))).isTrue();
        // Wrapped, because an SDK rarely throws its own reason at the top.
        assertThat(LlmErrors.isRateLimit(
                new IllegalStateException("call failed", new RuntimeException("429")))).isTrue();
    }

    @Test
    void isRateLimit_doesNotClaimUnrelatedFailures() {
        assertThat(LlmErrors.isRateLimit(new RuntimeException("connection reset"))).isFalse();
        assertThat(LlmErrors.isRateLimit(new RuntimeException("500 internal error"))).isFalse();
        // A null message must not throw while walking the cause chain.
        assertThat(LlmErrors.isRateLimit(new RuntimeException((String) null))).isFalse();
    }
}
