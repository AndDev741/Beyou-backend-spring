package beyou.beyouapp.backend.security.ratelimit;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;

/**
 * Spends one call of the notebook-ai bucket for a model call that does not come through
 * {@code /notebook/ai/**}: the assistant's card tool. Without it, one chat turn could draft cards
 * for page after page outside the hourly cap the notebook screens live under.
 *
 * <p>The bucket is the filter's own (same cache, same key), so a person who used their hour on the
 * page gets the same refusal from the chat, and the other way round. With rate limiting off (the
 * test and e2e profiles) there is no cache and nothing is spent.
 */
@Component
@RequiredArgsConstructor
public class NotebookAiQuota {

    private final ObjectProvider<Cache<String, Bucket>> rateLimitCache;

    public void spend(UUID userId) {
        Cache<String, Bucket> buckets = rateLimitCache.getIfAvailable();
        if (buckets == null) return;
        Bucket bucket = buckets.get(RateLimitConfig.notebookAiKey(userId), k -> RateLimitConfig.createNotebookAiBucket());
        if (!bucket.tryConsume(1)) {
            throw new BusinessException(ErrorKey.RATE_LIMIT_EXCEEDED,
                    "The notebook AI limit for this hour is used up. Try again later");
        }
    }
}
