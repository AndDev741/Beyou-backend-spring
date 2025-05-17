package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRoutineRepository extends JpaRepository<DiaryRoutine, UUID> {
}
