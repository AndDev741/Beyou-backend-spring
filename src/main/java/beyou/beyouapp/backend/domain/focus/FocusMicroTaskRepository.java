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

    /**
     * Serialises the writers of one person's list for one item.
     *
     * <p>Every insert into that list is a read of the list followed by a write the read decided
     * on (is the name there yet, where does the end sit), and under read committed two of those
     * running at once both read the list before either commits. Both then insert, and the
     * second one dies on {@code focus_micro_tasks_unique_per_item}. The input fires on Enter
     * and again on blur, so this happened on an ordinary add, not only across two tabs.
     *
     * <p>Advisory rather than a row lock: the row that would decide the race does not exist yet,
     * so there is nothing to {@code SELECT FOR UPDATE}. Released by the transaction ending, and
     * once it is held the next statement sees whatever the previous holder committed. Keyed on
     * the user and the item, not the day, because the day is derived from the user's timezone
     * inside the transaction. {@code hashtext} can collide; two unrelated lists sharing a key
     * wait on each other for one insert, which costs a few milliseconds and nothing else.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    void lockItemList(@Param("key") String key);

    /** Every micro-task the user ever wrote, by day and then list position. For the data export. */
    @Query("""
        SELECT t FROM FocusMicroTask t
        WHERE t.user.id = :userId
        ORDER BY t.taskDate ASC, t.orderIndex ASC, t.createdAt ASC
        """)
    List<FocusMicroTask> findAllForExport(@Param("userId") UUID userId);
}
