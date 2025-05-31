package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRoutineRepository extends JpaRepository<DiaryRoutine, UUID> {
    List<DiaryRoutine> findAllByUserId(UUID userId);

    Optional<DiaryRoutine> findByScheduleId(UUID scheduleId);

}
