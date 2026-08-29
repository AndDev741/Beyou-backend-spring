package beyou.beyouapp.backend.domain.focus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FocusMicroTaskRepository extends JpaRepository<FocusMicroTask, UUID> {

    /**
     * One item's list for one day, in the order the person put it in.
     *
     * <p>{@code createdAt} is the tiebreaker, not decoration: every row written before ordering
     * existed carries index 0, so without it a list nobody has dragged would come back in whatever
     * order the planner felt like.
     */
    @Query("""
        SELECT t FROM FocusMicroTask t
        WHERE t.user.id = :userId AND t.taskDate = :date AND t.itemGroup.id = :itemGroupId
        ORDER BY t.orderIndex ASC, t.createdAt ASC
        """)
    List<FocusMicroTask> findForItem(
        @Param("userId") UUID userId,
        @Param("date") LocalDate date,
        @Param("itemGroupId") UUID itemGroupId);

    /**
     * A whole day's, across every item.
     *
     * <p>What the snapshot reads. One query for the day rather than one per check row, which would
     * be an N+1 sized by the length of the routine.
     */
    @Query("""
        SELECT t FROM FocusMicroTask t
        LEFT JOIN FETCH t.itemGroup
        WHERE t.user.id = :userId AND t.taskDate = :date
        ORDER BY t.orderIndex ASC, t.createdAt ASC
        """)
    List<FocusMicroTask> findDay(@Param("userId") UUID userId, @Param("date") LocalDate date);

    /**
     * The distinct names this user has pinned, most recently created first, capped by the caller.
     *
     * <p>The pinned TEMPLATE set. Derived from the rows rather than kept in a second table, so
     * pinning is one flag on one row and there is no separate thing to keep in step. Server-side is
     * what makes it stop being per-device, which is the whole point of moving this off localStorage.
     */
    @Query("""
        SELECT t.name FROM FocusMicroTask t
        WHERE t.user.id = :userId AND t.pinned = true
        GROUP BY t.name
        ORDER BY MAX(t.createdAt) DESC
        """)
    List<String> findPinnedNames(@Param("userId") UUID userId, Pageable page);

    /** Every row of this user carrying one name, across all days and items. What pinning walks. */
    List<FocusMicroTask> findAllByUserIdAndName(UUID userId, String name);
}
