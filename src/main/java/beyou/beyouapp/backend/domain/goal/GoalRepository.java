package beyou.beyouapp.backend.domain.goal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GoalRepository extends JpaRepository<Goal, UUID> {
    /**
     * Loads goals and their categories in a single SELECT via LEFT JOIN.
     * Without {@code @EntityGraph}, accessing {@code goal.getCategories()} on
     * each result triggers a separate SELECT — classic N+1.
     */
    @EntityGraph(attributePaths = {"categories"})
    Optional<List<Goal>> findAllByUserId(UUID userId);
}