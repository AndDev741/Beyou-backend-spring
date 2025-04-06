package beyou.beyouapp.backend.domain.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task,UUID> {
    Optional<List<Task>> findAllByUserId(UUID userId);
}
