package beyou.beyouapp.backend.integration.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import beyou.beyouapp.backend.domain.common.UserCacheEvictService;

@SpringBootTest
@ActiveProfiles("test")
class CacheIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private UserCacheEvictService userCacheEvictService;

    private static final List<String> EXPECTED_DOMAIN_CACHES = List.of(
            "categories", "habits", "tasks", "goals",
            "routines", "routine", "todayRoutine", "schedules"
    );

    private static final String XP_BY_LEVEL_CACHE = "xpByLevel";

    @BeforeEach
    void clearAllCaches() {
        cacheManager.getCacheNames().forEach(name -> {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    @Test
    void cacheManagerRegistersAllExpectedCaches() {
        var registeredNames = cacheManager.getCacheNames();

        for (String expected : EXPECTED_DOMAIN_CACHES) {
            assertThat(registeredNames).contains(expected);
        }
        assertThat(registeredNames).contains(XP_BY_LEVEL_CACHE);
    }

    @Test
    void evictAllUserCachesClearsUserScopedCaches() {
        UUID userId = UUID.randomUUID();

        // Put a test value into the "categories" cache keyed by userId
        Cache categoriesCache = cacheManager.getCache("categories");
        assertThat(categoriesCache).isNotNull();
        categoriesCache.put(userId, "cachedCategoryData");

        // Verify it was stored
        assertThat(categoriesCache.get(userId)).isNotNull();
        assertThat(categoriesCache.get(userId).get()).isEqualTo("cachedCategoryData");

        // Evict all user caches
        userCacheEvictService.evictAllUserCaches(userId);

        // The entry should be gone
        assertThat(categoriesCache.get(userId)).isNull();
    }

    @Test
    void evictAllUserCachesClearsRoutineCache() {
        UUID userId = UUID.randomUUID();
        String compositeKey = userId + "_someRoutineId";

        // Put a test value into the "routine" cache with a composite key
        Cache routineCache = cacheManager.getCache("routine");
        assertThat(routineCache).isNotNull();
        routineCache.put(compositeKey, "cachedRoutineData");

        // Verify it was stored
        assertThat(routineCache.get(compositeKey)).isNotNull();

        // Evict all user caches — this should clear the entire routine cache
        userCacheEvictService.evictAllUserCaches(userId);

        // The routine cache should be cleared entirely (since it uses composite keys)
        assertThat(routineCache.get(compositeKey)).isNull();
    }

    @Test
    void xpByLevelCacheIsRegistered() {
        Cache xpByLevelCache = cacheManager.getCache(XP_BY_LEVEL_CACHE);
        assertThat(xpByLevelCache).isNotNull();

        // Verify the cache is functional
        xpByLevelCache.put("testKey", "testValue");
        assertThat(xpByLevelCache.get("testKey")).isNotNull();
        assertThat(xpByLevelCache.get("testKey").get()).isEqualTo("testValue");
    }

    @Test
    void domainCachesAreIndependent() {
        UUID userIdA = UUID.randomUUID();
        UUID userIdB = UUID.randomUUID();

        // Put values in two different domain caches for two different users
        Cache categoriesCache = cacheManager.getCache("categories");
        Cache habitsCache = cacheManager.getCache("habits");
        assertThat(categoriesCache).isNotNull();
        assertThat(habitsCache).isNotNull();

        categoriesCache.put(userIdA, "userA-categories");
        habitsCache.put(userIdB, "userB-habits");

        // Evict userA's caches
        userCacheEvictService.evictAllUserCaches(userIdA);

        // userA's categories entry should be gone
        assertThat(categoriesCache.get(userIdA)).isNull();

        // userB's habits entry should still be present (different cache, different user)
        assertThat(habitsCache.get(userIdB)).isNotNull();
        assertThat(habitsCache.get(userIdB).get()).isEqualTo("userB-habits");
    }
}
