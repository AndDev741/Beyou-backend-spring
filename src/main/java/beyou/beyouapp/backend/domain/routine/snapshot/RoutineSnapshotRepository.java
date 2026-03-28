package beyou.beyouapp.backend.domain.routine.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoutineSnapshotRepository extends JpaRepository<RoutineSnapshot, UUID> {
    Optional<RoutineSnapshot> findByRoutineIdAndSnapshotDate(UUID routineId, LocalDate snapshotDate);

    @Query("SELECT rs.snapshotDate FROM RoutineSnapshot rs WHERE rs.routine.id = :routineId AND rs.snapshotDate BETWEEN :startDate AND :endDate ORDER BY rs.snapshotDate")
    List<LocalDate> findSnapshotDatesByRoutineIdAndMonth(@Param("routineId") UUID routineId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT MAX(rs.snapshotDate) FROM RoutineSnapshot rs WHERE rs.routine.id = :routineId")
    Optional<LocalDate> findLatestSnapshotDateByRoutineId(@Param("routineId") UUID routineId);

    List<RoutineSnapshot> findAllByRoutineId(UUID routineId);

    boolean existsByUserIdAndSnapshotDateAndCompletedTrue(UUID userId, LocalDate snapshotDate);
}
