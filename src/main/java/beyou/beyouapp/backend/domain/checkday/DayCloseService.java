package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Closes one user's day: stamps an outcome on every owner that finished the day without a
 * row, so the strip has no unexplained holes (R5, R6).
 *
 * <p>KTD18 — this pass is <strong>insert-only</strong>. The request path
 * ({@code CheckItemService}) and the back-dated snapshot path ({@code SnapshotCheckService})
 * both upsert through {@link CheckDayRecorder}; this one never touches a row that already
 * exists. A presence outcome may therefore replace an absence outcome and never the reverse,
 * which is what makes the check-committing-just-after-midnight race harmless: whichever of
 * the two runs second, the day ends up {@code DONE}.
 *
 * <p>The write is a single {@code INSERT ... ON CONFLICT DO NOTHING} against
 * {@code uk_entity_check_day_owner_day} rather than a check-then-insert. The batched read
 * below already tells us which owners are missing, but a unique violation raised inside this
 * transaction would abort the <em>whole</em> transaction in Postgres, and the blast radius
 * is one user's entire day across every owner. Letting the database absorb the collision
 * costs nothing and removes that cliff. No advisory lock is taken (unlike
 * {@link CheckDayRecorder}): the conflict clause is the serialisation point, and an owner
 * whose insert loses the race is skipped entirely, scalars included — the writer that won
 * recomputed them already.
 *
 * <p>R11/KTD19 — an owner that was not scheduled, or belongs to no routine at all, gets a
 * neutral row rather than a {@code MISSED}, so a day the user was never asked to act on
 * cannot break a streak. A day this pass never reached keeps no row at all and reads as
 * unknown, which is why the caller closes only the day that just ended and never walks
 * backwards: stamping {@code MISSED} across a retroactive window would invent failures for
 * days an entity did not exist for.
 *
 * <p>Nothing here reads live {@code HabitGroupCheck} / {@code TaskGroupCheck} rows.
 * {@code SnapshotCheckMigrator} bulk-deletes them for a date once they are copied into the
 * snapshot, so a pass that re-derived presence from them would see an empty day and write
 * {@code MISSED} over a real {@code DONE}. The {@code entity_check_day} rows the request
 * path already wrote are the only presence evidence consulted.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DayCloseService {

    /**
     * Named-constraint conflict target rather than a column list, so the statement fails
     * loudly if {@code uk_entity_check_day_owner_day} is ever renamed instead of silently
     * turning into an unguarded insert. Every value binds as text and is cast in SQL: the
     * column types (uuid, date) are then chosen by Postgres rather than by JDBC parameter
     * inference, matching {@code EntityCheckDayRepository.lockCheckOwner}.
     */
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO entity_check_day (id, user_id, owner_type, owner_id, day, outcome)
            VALUES (CAST(:id AS uuid), CAST(:userId AS uuid), :ownerType,
                    CAST(:ownerId AS uuid), CAST(:day AS date), :outcome)
            ON CONFLICT ON CONSTRAINT uk_entity_check_day_owner_day DO NOTHING
            """;

    private final EntityCheckDayRepository entityCheckDayRepository;
    private final HabitRepository habitRepository;
    private final TaskRepository taskRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final UserCacheEvictService userCacheEvictService;
    private final EntityManager entityManager;

    /**
     * Writes the closing day's outcome for every owner of {@code account} that has none.
     *
     * <p>Runs once per user per date, deliberately outside any routine loop: a habit sitting
     * in two routines is one owner and gets exactly one row, and its standing is judged
     * against every routine the user owns at once.
     *
     * @param account the user whose day is closing. Reloaded here because the scheduler
     *                lists users outside a transaction and hands over a detached instance —
     *                mutating a detached {@code CheckProgress} would never reach the database.
     * @param day     the day being closed, already resolved in that user's timezone (R15).
     * @return how many rows were inserted. Zero means the day was already fully accounted
     *         for, which is the normal result of a second run.
     */
    @Transactional
    public int closeDay(User account, LocalDate day) {
        if (account == null || account.getId() == null) {
            throw new IllegalArgumentException("Closing a day needs the owning user");
        }
        if (day == null) {
            throw new IllegalArgumentException("Closing a day needs the day to close");
        }

        User user = entityManager.find(User.class, account.getId());
        if (user == null) {
            log.warn("User {} no longer exists — nothing to close for {}", account.getId(), day);
            return 0;
        }

        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(user.getId());

        // One read for the whole user-day, then a diff in memory. Asking per owner would be
        // an N+1 over every entity of every user, every night.
        Set<OwnerKey> alreadyRecorded = new HashSet<>();
        for (EntityCheckDay row : entityCheckDayRepository.findByUserIdAndDay(user.getId(), day)) {
            alreadyRecorded.add(new OwnerKey(row.getOwnerType(), row.getOwnerId()));
        }

        LocalDate today = UserDateResolver.today(user);
        int written = 0;
        for (Owner owner : ownersOf(user, routines)) {
            if (alreadyRecorded.contains(new OwnerKey(owner.type(), owner.id()))) {
                continue;
            }
            if (owner.existsFrom() != null && owner.existsFrom().isAfter(day)) {
                // The owner did not exist yet. A row here would be invented history, and a
                // MISSED among it would be an invented failure.
                continue;
            }
            if (closeOwnerDay(user, owner, day, routines, today)) {
                written++;
            }
        }

        if (written > 0) {
            // The pass runs with no request behind it, so nothing else will drop this user's
            // reads. Only the user-scoped caches: the shared `routine` cache is cleared once
            // by the caller when the whole batch ends, not once per user.
            userCacheEvictService.evictUserScopedCaches(user.getId());
        }

        log.info("Closed day {} for user {} — {} absence rows written", day, user.getId(), written);
        return written;
    }

    /**
     * Stamps one owner's absence and re-derives its scalars.
     *
     * <p>The history is read before the insert and the new row appended in memory, so the
     * recompute needs one query per owner written rather than a second read-back. The read
     * is exact: the diff proved this owner has no row for the day, and the calculator keys
     * rows by day anyway, so a duplicate could not double-count.
     *
     * @return whether a row was actually inserted. {@code false} means another writer got
     *         there first, in which case that writer owns the scalars too.
     */
    private boolean closeOwnerDay(User user, Owner owner, LocalDate day,
                                  List<DiaryRoutine> routines, LocalDate today) {
        // dayClosed is true by construction — the caller only ever passes a day that has
        // ended in this user's timezone, which is the whole reason MISSED can be stamped
        // here and not on the uncheck path.
        CheckDayOutcome outcome = CheckDayRecorder.absenceOutcome(
                ScheduledOnDayResolver.standingOf(owner.type(), owner.id(), routines, day), true);

        List<EntityCheckDay> history = new ArrayList<>(entityCheckDayRepository
                .findByOwnerTypeAndOwnerIdOrderByDayAsc(owner.type(), owner.id()));

        if (insertIfAbsent(user, owner, day, outcome) == 0) {
            log.debug("A row for {} {} on {} appeared while the day was closing — left alone",
                    owner.type(), owner.id(), day);
            return false;
        }
        history.add(new EntityCheckDay(user, owner.type(), owner.id(), day, outcome));

        CheckProgress progress = owner.progress();
        // Anchored on the user's today, not on the day just closed — same contract as
        // CheckDayRecorder. These scalars mean "as of now".
        CheckProgress recomputed = CheckProgressCalculator.recompute(
                history, today, progress != null ? progress.getBestStreak() : 0);
        if (progress != null) {
            copyInto(recomputed, progress);
        }

        log.debug("Closed {} {} on {} as {} — streak {}, total {}",
                owner.type(), owner.id(), day, outcome,
                recomputed.getCurrentStreak(), recomputed.getTotalCheckIns());
        return true;
    }

    private int insertIfAbsent(User user, Owner owner, LocalDate day, CheckDayOutcome outcome) {
        return entityManager.createNativeQuery(INSERT_IF_ABSENT)
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("userId", user.getId().toString())
                .setParameter("ownerType", owner.type().name())
                .setParameter("ownerId", owner.id().toString())
                .setParameter("day", day.toString())
                .setParameter("outcome", outcome.name())
                .executeUpdate();
    }

    /**
     * Everything of this user's that carries a streak: every habit, every recurring task,
     * every routine, and the account itself.
     *
     * <p>One-time tasks are left out (R4): they are checked once and deleted the day after,
     * so a streak over one is a streak of one that nothing can extend, and closing days for
     * them would leave orphan history behind a deleted row.
     *
     * <p>{@code existsFrom} is the floor a row may not be written below. {@code Habit} and
     * {@code Task} carry their own {@code createdAt}; {@code Routine} has no such column, so
     * a routine is floored at the account's creation date instead — the only existence
     * evidence available. That leaves one residual case: a routine created between midnight
     * and the grace hour receives a row for the day before it existed. It is accepted rather
     * than engineered around, because the pass only ever closes the day that just ended, and
     * such a row lands at the very start of that routine's history where
     * {@link CheckProgressCalculator}'s streak walk terminates regardless. Widening the
     * closing window would make this wrong and would need a real {@code created_at} on
     * {@code routines} first.
     */
    private List<Owner> ownersOf(User user, List<DiaryRoutine> routines) {
        List<Owner> owners = new ArrayList<>();
        LocalDate accountCreated = toLocalDate(user.getCreatedAt());

        for (Habit habit : habitRepository.findAllByUserId(user.getId())) {
            owners.add(new Owner(CheckDayOwnerType.HABIT, habit.getId(),
                    toLocalDate(habit.getCreatedAt()), habit.getCheckProgress()));
        }
        for (Task task : taskRepository.findAllByUserId(user.getId()).orElseGet(List::of)) {
            if (task.isOneTimeTask()) {
                continue;
            }
            owners.add(new Owner(CheckDayOwnerType.TASK, task.getId(),
                    toLocalDate(task.getCreatedAt()), task.getCheckProgress()));
        }
        for (DiaryRoutine routine : routines) {
            owners.add(new Owner(CheckDayOwnerType.ROUTINE, routine.getId(),
                    accountCreated, routine.getCheckProgress()));
        }
        owners.add(new Owner(CheckDayOwnerType.USER, user.getId(),
                accountCreated, user.getCheckProgress()));
        return owners;
    }

    /**
     * {@code createdAt} is stamped in the server's zone by the entities' {@code @PrePersist},
     * not the user's, so this floor can sit a day either side of the user's own calendar. It
     * is applied leniently ({@code isAfter}, not {@code !isBefore}) for that reason: losing a
     * real day is worse than one extra neutral row on an entity's first day.
     */
    private static LocalDate toLocalDate(java.sql.Date date) {
        return date != null ? date.toLocalDate() : null;
    }

    private static void copyInto(CheckProgress source, CheckProgress target) {
        target.setCurrentStreak(source.getCurrentStreak());
        target.setBestStreak(source.getBestStreak());
        target.setTotalCheckIns(source.getTotalCheckIns());
        target.setFirstCheckInDate(source.getFirstCheckInDate());
        target.setLastCheckInDate(source.getLastCheckInDate());
    }

    /** One checkable thing and the scalars the recompute writes back onto it. */
    private record Owner(CheckDayOwnerType type, UUID id, LocalDate existsFrom, CheckProgress progress) {}

    /** The half of {@code uk_entity_check_day_owner_day} that identifies an owner. */
    private record OwnerKey(CheckDayOwnerType type, UUID id) {}
}
