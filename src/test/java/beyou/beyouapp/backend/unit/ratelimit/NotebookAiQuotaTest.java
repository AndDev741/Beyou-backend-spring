package beyou.beyouapp.backend.unit.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.ratelimit.NotebookAiQuota;
import beyou.beyouapp.backend.security.ratelimit.RateLimitConfig;
import io.github.bucket4j.Bucket;

/** The assistant's card tool spends the notebook screens' own hourly bucket, not a fresh one. */
class NotebookAiQuotaTest {

    private final UUID userId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private NotebookAiQuota quota(Cache<String, Bucket> cache) {
        ObjectProvider<Cache<String, Bucket>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cache);
        return new NotebookAiQuota(provider);
    }

    @Test
    void anHourUsedOnThePageIsUsedUpForTheChatToo() {
        Cache<String, Bucket> cache = Caffeine.newBuilder().build();
        Bucket filterBucket = cache.get(RateLimitConfig.notebookAiKey(userId.toString()), k -> RateLimitConfig.createNotebookAiBucket());
        filterBucket.tryConsume(RateLimitConfig.NOTEBOOK_AI_CALLS_PER_HOUR - 1);

        NotebookAiQuota quota = quota(cache);
        quota.spend(userId);

        assertThatThrownBy(() -> quota.spend(userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.RATE_LIMIT_EXCEEDED);
        assertThat(filterBucket.getAvailableTokens()).isZero();
    }

    @Test
    void withRateLimitingOffNothingIsSpent() {
        NotebookAiQuota quota = quota(null);
        for (int i = 0; i < RateLimitConfig.NOTEBOOK_AI_CALLS_PER_HOUR + 5; i++) {
            quota.spend(userId);
        }
    }
}
