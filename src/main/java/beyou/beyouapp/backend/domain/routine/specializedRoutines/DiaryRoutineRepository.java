package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRoutineRepository extends JpaRepository<DiaryRoutine, UUID> {
    /**
     * Loads routines and their sections in a single SELECT via LEFT JOIN.
     * Without {@code @EntityGraph}, accessing {@code routine.getRoutineSections()}
     * on each result triggers a separate SELECT per routine — classic N+1.
     * Section's taskGroups/habitGroups remain lazy (with @BatchSize on each).
     */
    @EntityGraph(attributePaths = {"routineSections"})
    List<DiaryRoutine> findAllByUserId(UUID userId);

    Optional<DiaryRoutine> findByScheduleId(UUID scheduleId);

}
