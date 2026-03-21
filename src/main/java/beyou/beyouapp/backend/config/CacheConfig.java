package beyou.beyouapp.backend.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        //Global fallback
        manager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(30)
                .expireAfterWrite(Duration.ofMinutes(120))
                .recordStats()
        );

        return manager;
    }

    private Cache<Object, Object> buildCache(int maxSize, Duration ttl) {
        return Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttl)
            .recordStats() //Metrics
            .build();
    }
}
