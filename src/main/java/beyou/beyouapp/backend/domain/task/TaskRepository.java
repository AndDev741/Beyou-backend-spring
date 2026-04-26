package beyou.beyouapp.backend.domain.task;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task,UUID> {
    /**
     * Loads tasks and their categories in a single SELECT via LEFT JOIN.
     * Without {@code @EntityGraph}, accessing {@code task.getCategories()} on
     * each result triggers a separate SELECT — classic N+1.
     */
    @EntityGraph(attributePaths = {"categories"})
    Optional<List<Task>> findAllByUserId(UUID userId);

    List<Task> findAllByMarkedToDeleteBefore(LocalDate date);
}
