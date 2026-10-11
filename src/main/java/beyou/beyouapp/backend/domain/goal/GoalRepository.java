package beyou.beyouapp.backend.domain.goal;

import java.time.LocalDate;
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

    /**
     * The goals ending inside a window of days, for the calendar. Archived goals are left out:
     * archiving is how someone puts a goal away, and the clients already hide them from the
     * goals page.
     */
    List<GoalDeadline> findDeadlinesByUserIdAndEndDateBetweenAndArchivedAtIsNull(
            UUID userId, LocalDate from, LocalDate to);
}