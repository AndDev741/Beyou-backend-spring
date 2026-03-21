package beyou.beyouapp.backend.domain.category.xpbylevel;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface XpByLevelRepository extends JpaRepository<XpByLevel, Integer> {
    @Cacheable(cacheNames = "xpByLevel", key = "#level")
    XpByLevel findByLevel(int level);
}
