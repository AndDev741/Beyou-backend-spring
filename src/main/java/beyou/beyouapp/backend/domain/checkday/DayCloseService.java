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
 * costs nothing and removes that cliff. An owner whose insert loses the race is skipped
 * entirely, scalars included — the writer that won recomputed them already.
 *
 * <p>The conflict clause is <strong>not</strong> a substitute for the advisory lock
 * {@link CheckDayRecorder} takes, and this pass takes the same one through
 * {@link CheckOwnerLock}. The clause only serialises two writers colliding on one day. This
 * pass writes <em>yesterday</em> while the request path writes <em>today</em>: different
 * unique keys, so the clause never fires between them and nothing would order the two
 * recomputes of the same owner's scalars. The lost update is concrete — the pass reads a
 * habit's history at 02:00:00, a check lands at 02:00:00.1 and commits streak 4, and the
 * pass then commits streak 0 from its stale read. No {@code @Version} exists anywhere in
 * the model to catch it, and the next check would pay its XP bonus against the wrong
 * streak. The cost is that one user's check-ins queue behind that user's own close, which
 * runs once a night and touches one account.
 *
 * <p><strong>Known caveat, not yet closed.</strong> The two paths reach the advisory lock
 * with opposite row-lock orders. {@code lockCheckOwner} is a native query, so Hibernate
 * auto-flushes before it; on the request path the XP update has already dirtied
 * {@code users}, so that transaction holds the {@code users} row lock <em>before</em> it
 * asks for the advisory lock. This pass has nothing dirty when it takes the advisory lock
 * and only writes {@code users} at commit. A check landing in that window can therefore
 * deadlock against the pass. Postgres detects it and aborts one side, so nothing is
 * corrupted: the check surfaces one error, or the pass loses that user's day and the
 * per-user try/catch in {@code RoutineSnapshotScheduler} logs it, leaving the day with no
 * rows — unknown, which R18 already reads as neutral. Closing this properly means making
 * one of the two paths change its acquisition order, which is a wider change than the lock
 * itself.
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
        for (Owner owner : ownersOf(user)) {
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

        log.info("Closed day {} for user {} — {} rows written", day, user.getId(), written);
        return written;
    }

    /**
     * Stamps one owner's outcome for the day and re-derives its scalars.
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
        // Before the history read, not after: the read is the first half of a read-modify-
        // write over this owner's scalars, and a check committing between the two is exactly
        // the lost update this lock exists to stop. Same keys, same order as the request
        // path — see CheckOwnerLock.
        CheckOwnerLock.takeUserThenOwner(entityCheckDayRepository, user.getId(),
                owner.type(), owner.id());

        CheckDayOutcome outcome = presenceOutcome(user, owner, day);
        if (outcome == null) {
            // dayClosed is true by construction — the caller only ever passes a day that has
            // ended in this user's timezone, which is the whole reason MISSED can be stamped
            // here and not on the uncheck path.
            outcome = CheckDayRecorder.absenceOutcome(
                    ScheduledOnDayResolver.standingOf(owner.type(), owner.id(), routines, day), true);
        }

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

    /**
     * The presence outcome for an owner that finished the day having actually done
     * something, or {@code null} when the caller should fall through to an absence.
     *
     * <p>Only the account has one. {@code User.completedDays} is the completion record the
     * app already keeps — {@code UserService.markDayCompleted} / {@code unmarkDayComplete}
     * maintain it under whichever {@code ConstanceConfiguration} the user chose, and
     * {@link UserStreakService} reads it to answer this same question. Without this branch
     * every account row is an absence: a user who finished their whole Wednesday gets
     * {@code USER/MISSED} stamped on it at the grace hour, {@code GET /check-history} reads
     * back a wall of {@code MISSED}, and {@code users.check_*} is rewritten to zero nightly
     * because {@link CheckProgressCalculator} only counts {@code DONE}.
     *
     * <p>Habits and tasks need no equivalent: the request path already wrote their
     * {@code DONE} rows, and the diff in {@link #closeDay} skips any owner that has one.
     * The snapshot tables are deliberately not consulted (R19).
     */
    private static CheckDayOutcome presenceOutcome(User user, Owner owner, LocalDate day) {
        if (owner.type() != CheckDayOwnerType.USER) {
            return null;
        }
        Set<LocalDate> completedDays = user.getCompletedDays();
        return completedDays != null && completedDays.contains(day) ? CheckDayOutcome.DONE : null;
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
     * Everything of this user's that this pass can say something true about: every habit,
     * every recurring task, and the account itself.
     *
     * <p>One-time tasks are left out (R4): they are checked once and deleted the day after,
     * so a streak over one is a streak of one that nothing can extend, and closing days for
     * them would leave orphan history behind a deleted row.
     *
     * <p><strong>Routines are left out too, and that is the point of this note.</strong>
     * Habits and tasks get their {@code DONE} rows from the request path and the account
     * gets one from {@link #presenceOutcome}; a routine has no presence writer anywhere, and
     * there is no cheap way to derive one that does not read the snapshot tables (R19
     * forbids it). Everything this pass could write for a routine is therefore an absence,
     * including on days the routine was completed in full — a permanent row asserting a
     * failure that did not happen. An absent row reads as unknown (R18), which is the honest
     * answer while nothing can tell the difference. {@code CheckDayOwnerType.ROUTINE} stays
     * in the enum and {@code GET /check-history?ownerType=ROUTINE} still accepts it; it
     * answers all-unknown by design. Routine rows come back here when something actually
     * needs them <em>and</em> a presence writer exists — both, not either.
     *
     * <p>{@code existsFrom} is the floor a row may not be written below. {@code Habit} and
     * {@code Task} carry their own {@code createdAt}; the account is floored at its own
     * creation date.
     */
    private List<Owner> ownersOf(User user) {
        List<Owner> owners = new ArrayList<>();

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
        owners.add(new Owner(CheckDayOwnerType.USER, user.getId(),
                toLocalDate(user.getCreatedAt()), user.getCheckProgress()));
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
