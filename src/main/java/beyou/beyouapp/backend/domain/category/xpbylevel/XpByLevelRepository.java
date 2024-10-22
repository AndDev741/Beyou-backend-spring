package beyou.beyouapp.backend.domain.category.xpbylevel;

import org.springframework.data.jpa.repository.JpaRepository;

public interface XpByLevelRepository extends JpaRepository<XpByLevel, Integer> {
    XpByLevel findByLevel(int level);
}
