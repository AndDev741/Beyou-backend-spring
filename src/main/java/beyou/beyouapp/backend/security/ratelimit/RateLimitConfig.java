package beyou.beyouapp.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Bean
    public Cache<String, Bucket> rateLimitCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();
    }

    public static Bucket createAuthBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(5)
                        .refillGreedy(5, Duration.ofMinutes(15))
                        .build())
                .build();
    }

    public static Bucket createDomainWriteBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(30)
                        .refillGreedy(30, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    public static Bucket createDomainReadBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(60)
                        .refillGreedy(60, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    /**
     * AI agent chat streams: each opens a long-lived SSE emitter, calls the LLM,
     * and runs a tool loop — expensive, but conversational, so more generous than
     * one-shot generation. 30/hour per user caps external-billing abuse while
     * leaving room for a real back-and-forth.
     */
    public static Bucket createAgentChatBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(30)
                        .refillGreedy(30, Duration.ofHours(1))
                        .build())
                .build();
    }

    /** Feedback submissions allowed per user per hour — see {@link #createFeedbackBucket()}. */
    public static final int FEEDBACK_SUBMISSIONS_PER_HOUR = 10;

    /**
     * Feedback submission (POST /feedback) gets its own per-user bucket rather
     * than competing with routine domain writes: a burst of feedback must not
     * eat the budget a user needs to check off habits, and a spammer must not
     * be able to flood the admin queue on the generous write allowance.
     * Ten an hour is far more than an honest user ever needs.
     */
    public static Bucket createFeedbackBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(FEEDBACK_SUBMISSIONS_PER_HOUR)
                        .refillGreedy(FEEDBACK_SUBMISSIONS_PER_HOUR, Duration.ofHours(1))
                        .build())
                .build();
    }

    /** Attachment uploads allowed per user per hour — see {@link #createFeedbackAttachmentBucket()}. */
    public static final int FEEDBACK_ATTACHMENT_UPLOADS_PER_HOUR = 20;

    /**
     * Attachment upload (POST /feedback/{id}/attachments) gets its own per-user
     * bucket, ahead of the generic write branch, because it is nothing like a
     * generic write. Every call decodes an image, allocates the full raster,
     * downscales it and re-encodes it to JPEG — tens of megabytes of transient
     * heap per request, before the 25 MP pre-decode ceiling is even reached. At
     * the generic 30-per-minute write allowance a single authenticated user
     * could hold the server at that cost indefinitely.
     *
     * Twenty an hour is well past honest use: the cap is five attachments per
     * submission, so this is four fully illustrated reports in an hour, on top
     * of a submission budget that only allows ten.
     */
    public static Bucket createFeedbackAttachmentBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(FEEDBACK_ATTACHMENT_UPLOADS_PER_HOUR)
                        .refillGreedy(FEEDBACK_ATTACHMENT_UPLOADS_PER_HOUR, Duration.ofHours(1))
                        .build())
                .build();
    }

    /** Data exports allowed per user per hour — see {@link #createUserExportBucket()}. */
    public static final int USER_EXPORT_DOWNLOADS_PER_HOUR = 5;

    /**
     * {@code GET /user/export} — the whole account in one response.
     *
     * <p>It is a GET, so it used to land in the generic read bucket at 60 a minute. That
     * budget is sized for the reads a screen makes: fetch a list, fetch a routine. This
     * request is not that. It reads every category, habit, task, goal, routine, feedback
     * thread and assistant conversation the account has, assembles the lot in memory as
     * one map, and serializes it in one go — the response is unbounded in the size of the
     * account and there is no pagination to hide behind. Sixty a minute of that from a
     * single authenticated user is a comfortable way to hold the heap.
     *
     * <p>Five an hour, because of what the endpoint is for. Someone takes their data
     * when they are leaving, or once out of curiosity; nobody needs a sixth copy inside
     * the hour. The allowance is there so a failed download can be retried a few times,
     * not so the file can be fetched repeatedly.
     *
     * <p>The N+1 work that shipped alongside this made the export flat in query count
     * rather than linear, which lowers the cost per call but not the shape of the risk:
     * the payload still grows with the account, so the limit stays.
     */
    public static Bucket createUserExportBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(USER_EXPORT_DOWNLOADS_PER_HOUR)
                        .refillGreedy(USER_EXPORT_DOWNLOADS_PER_HOUR, Duration.ofHours(1))
                        .build())
                .build();
    }

    /** Header-less GET /user/photo/** (signed URL, no JWT) — per-IP so callers can't flood disk reads. */
    public static Bucket createPhotoBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(120)
                        .refillGreedy(120, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    public static Bucket createDocsBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(30)
                        .refillGreedy(30, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    /** Deletion-code confirms allowed per user per hour — see {@link #createAccountDeletionBucket()}. */
    public static final int ACCOUNT_DELETION_ATTEMPTS_PER_HOUR = 10;

    /**
     * Asking for, and spending, an account deletion code.
     *
     * These endpoints used to share the generic 30-per-minute write bucket, which is
     * sized for checking off habits: roughly 450 silent guesses inside one code's
     * fifteen-minute life. The code itself caps wrong tries at five, so this is the
     * second wall rather than the first, and it is what keeps the cost of walking a
     * six-digit space in the thousands of years rather than the days. Ten an hour is
     * more than anyone deleting an account once will ever need.
     */
    public static Bucket createAccountDeletionBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(ACCOUNT_DELETION_ATTEMPTS_PER_HOUR)
                        .refillGreedy(ACCOUNT_DELETION_ATTEMPTS_PER_HOUR, Duration.ofHours(1))
                        .build())
                .build();
    }
}
