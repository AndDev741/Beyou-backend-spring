package beyou.beyouapp.backend.config;

import java.time.Duration;
import java.util.List;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final List<String> DOMAIN_CACHES = List.of(
        "categories", "habits", "tasks", "goals",
        "routines", "routine", "todayRoutine", "schedules"
    );

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Global fallback (docs caches): 30 max, 120min TTL
        manager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(30)
                .expireAfterWrite(Duration.ofMinutes(120))
                .recordStats()
        );

        // Tier 1: Domain caches — 500 max, 30min TTL
        for (String cacheName : DOMAIN_CACHES) {
            manager.registerCustomCache(cacheName,
                Caffeine.newBuilder()
                    .maximumSize(500)
                    .expireAfterWrite(Duration.ofMinutes(30))
                    .recordStats()
                    .build());
        }

        // Tier 2: Reference cache — 100 max, no expiry
        manager.registerCustomCache("xpByLevel",
            Caffeine.newBuilder()
                .maximumSize(100)
                .recordStats()
                .build());

        return manager;
    }
}
