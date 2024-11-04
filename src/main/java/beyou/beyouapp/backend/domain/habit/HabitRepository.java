package beyou.beyouapp.backend.domain.habit;

import java.util.ArrayList;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HabitRepository extends JpaRepository<Habit, UUID> {
    ArrayList<Habit> findAllByUserId(UUID userId);
}
