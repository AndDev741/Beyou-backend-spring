package beyou.beyouapp.backend.domain.common;

import java.util.UUID;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserCacheEvictService {

    private final CacheManager cacheManager;

    @Caching(evict = {
        @CacheEvict(cacheNames = "categories", key = "#userId"),
        @CacheEvict(cacheNames = "habits", key = "#userId"),
        @CacheEvict(cacheNames = "tasks", key = "#userId"),
        @CacheEvict(cacheNames = "goals", key = "#userId"),
        @CacheEvict(cacheNames = "routines", key = "#userId"),
        @CacheEvict(cacheNames = "todayRoutine", key = "#userId"),
        @CacheEvict(cacheNames = "schedules", key = "#userId")
    })
    public void evictAllUserCaches(UUID userId) {
        // AOP handles the @CacheEvict annotations above.
        // Programmatic eviction for 'routine' cache (composite key: userId_routineId)
        clearSharedRoutineCache();
    }

    /**
     * The user-scoped half of {@link #evictAllUserCaches}, without the shared {@code routine}
     * cache clear.
     *
     * <p>Exists for the day-close batch, which touches every user in a timezone in one pass.
     * Calling {@code evictAllUserCaches} there would clear the whole shared {@code routine}
     * cache once per user — for a thousand users, a thousand full clears of a cache that only
     * needed clearing once. The batch calls this inside its loop and
     * {@link #clearSharedRoutineCache()} once when the loop ends.
     *
     * <p>The annotation block is repeated rather than delegated on purpose: a self-invocation
     * would not pass through the Spring proxy, so {@code @CacheEvict} would silently do
     * nothing. Any cache added to one block has to be added to the other.
     */
    @Caching(evict = {
        @CacheEvict(cacheNames = "categories", key = "#userId"),
        @CacheEvict(cacheNames = "habits", key = "#userId"),
        @CacheEvict(cacheNames = "tasks", key = "#userId"),
        @CacheEvict(cacheNames = "goals", key = "#userId"),
        @CacheEvict(cacheNames = "routines", key = "#userId"),
        @CacheEvict(cacheNames = "todayRoutine", key = "#userId"),
        @CacheEvict(cacheNames = "schedules", key = "#userId")
    })
    public void evictUserScopedCaches(UUID userId) {
        // AOP handles the @CacheEvict annotations above; nothing else to do here.
    }

    /**
     * Clears the shared {@code routine} cache, whose keys are composite
     * ({@code userId_routineId}) and therefore not evictable per user.
     */
    public void clearSharedRoutineCache() {
        Cache routineCache = cacheManager.getCache("routine");
        if (routineCache != null) {
            routineCache.clear();
        }
    }
}
