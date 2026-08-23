package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRoutineRepository extends JpaRepository<DiaryRoutine, UUID> {
    /**
     * Loads routines, their sections and their schedule in a single SELECT via LEFT JOIN.
     * Without {@code @EntityGraph}, accessing {@code routine.getRoutineSections()}
     * on each result triggers a separate SELECT per routine — classic N+1.
     * Section's taskGroups/habitGroups remain lazy (with @BatchSize on each).
     *
     * <p>{@code schedule} is in the graph for the same reason, one level less obvious:
     * it is an EAGER {@code @OneToOne}, and eager to-one associations are NOT joined
     * into a derived query — Hibernate resolves each one with its own follow-up select,
     * so a list of routines cost a select per routine whether or not anything read the
     * schedule. Naming it here turns that back into part of the same join. Safe next to
     * {@code routineSections}: only two <em>bag</em> fetches collide, and this is a
     * to-one. The days inside it are batched — see {@code Schedule.days}.
     */
    @EntityGraph(attributePaths = {"routineSections", "schedule"})
    List<DiaryRoutine> findAllByUserId(UUID userId);

    Optional<DiaryRoutine> findByScheduleId(UUID scheduleId);

}
