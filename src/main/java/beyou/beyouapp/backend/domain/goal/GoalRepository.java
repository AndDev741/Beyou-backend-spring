package beyou.beyouapp.backend.domain.goal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GoalRepository extends JpaRepository<Goal, UUID> {
    Optional<List<Goal>> findAllByUserId(UUID userId);
}