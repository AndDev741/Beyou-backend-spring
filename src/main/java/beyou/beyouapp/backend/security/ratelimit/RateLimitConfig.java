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

    /** Public, unauthenticated GET /user/photo/** — per-IP so anonymous callers can't flood disk reads. */
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
}
