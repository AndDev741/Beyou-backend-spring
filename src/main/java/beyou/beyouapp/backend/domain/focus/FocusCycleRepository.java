package beyou.beyouapp.backend.domain.focus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FocusCycleRepository extends JpaRepository<FocusCycle, UUID> {

    /**
     * A day's completed cycles, oldest first.
     *
     * <p>Fetches the item group with it: the history screen names the item each cycle ran on, and a
     * lazy proxy per row would be an N+1 the moment somebody has a productive morning.
     */
    @Query("""
        SELECT c FROM FocusCycle c
        LEFT JOIN FETCH c.itemGroup
        WHERE c.user.id = :userId AND c.cycleDate = :date
        ORDER BY c.startedAt ASC
        """)
    List<FocusCycle> findDay(@Param("userId") UUID userId, @Param("date") LocalDate date);

    /**
     * Completed pomodoro minutes run on any of these notebook pages. Breaks do not count: a node's
     * "3h 20m focused" is time spent on it, not time spent resting from it.
     */
    @Query("""
        SELECT COALESCE(SUM(c.minutes), 0) FROM FocusCycle c
        WHERE c.user.id = :userId AND c.notebookPageId IN :pageIds
          AND c.kind = beyou.beyouapp.backend.domain.focus.CycleKind.POMODORO
        """)
    long sumPomodoroMinutes(@Param("userId") UUID userId,
                            @Param("pageIds") java.util.Collection<UUID> pageIds);
}
