package beyou.beyouapp.backend.notification.engagement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayCalculator;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferences;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides and sends one account's nudge, under a budget.
 *
 * <p>Split from {@link EngagementNudgeScheduler} because the two answer different
 * questions: the scheduler decides <em>whose</em> turn it is and when, this decides
 * <em>what</em> and <em>whether</em>. Everything below is per-account and none of it needs
 * to know a cron expression.
 *
 * <p><b>Send first, record second.</b> The row that suppresses tomorrow's duplicate is
 * written only after the mail is handed over. Writing it first would be safer against
 * double-sends and much worse in practice: a failed send would leave a row claiming the
 * mail went out, and the nudge would be silently suppressed for the rest of the day with
 * nothing to notice. The reverse risk — a send that succeeds and a row that fails — is one
 * duplicate at worst, and the unique constraint means the second attempt is refused rather
 * than duplicated again.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EngagementNudgeService {

    private final EntityCheckDayRepository checkDayRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final UserStreakService userStreakService;
    private final XpDecayCalculator xpDecayCalculator;
    private final NotificationPreferencesService preferencesService;
    private final NotificationSendRepository sendRepository;
    private final EmailService emailService;

    /**
     * The backfill window, mirroring {@code RoutineSnapshotScheduler.MAX_BACKFILL_DAYS}.
     * Configurable so the two can be moved together, and so a test can shrink it — but the
     * default has to match, because a nudge about a day the snapshot job will not accept a
     * check for is a lie.
     */
    @Value("${engagement.backfill-days:7}")
    private int backfillDays;

    /** The shortest run worth writing to somebody about. */
    @Value("${engagement.min-streak-to-defend:3}")
    private int minStreakToDefend;

    /** How far below the record still counts as "at risk". */
    @Value("${engagement.record-gap:2}")
    private int recordGap;

    /**
     * The minimum gap, in the reader's days, between any two engagement mails to one
     * account. Two triggers can each be individually justified on the same morning; the
     * sum is not.
     */
    @Value("${engagement.min-days-between:3}")
    private int minDaysBetween;

    /**
     * How many engagement mails may go out in one day across every account.
     *
     * <p>Sized well under the mail provider's free-tier ceiling on purpose: the same
     * allowance carries verification, password resets and deletion codes, which people
     * actually asked for. A reset that does not arrive because a nudge spent the budget is
     * a far worse failure than a nudge that never goes out, so the nudges get the smaller
     * half.
     */
    @Value("${engagement.daily-cap:100}")
    private int dailyCap;

    /**
     * Considers one account and sends at most one mail.
     *
     * @return true when a mail was sent, so the caller can count against the cap without
     *         re-reading it per account
     */
    @Transactional
    public boolean considerAccount(User user, LocalDate ownerToday) {
        if (!isReachable(user)) {
            return false;
        }

        NotificationPreferences preferences = preferencesService.getOrCreate(user);
        if (!preferences.isEngagementEmail()) {
            return false;
        }

        if (mailedTooRecently(user, ownerToday)) {
            return false;
        }

        Optional<NudgeDecision> decision = decideFor(user, ownerToday);
        if (decision.isEmpty()) {
            return false;
        }

        NudgeDecision nudge = decision.get();
        if (sendRepository.existsByUserIdAndKindAndSentOn(user.getId(), nudge.kind(), ownerToday)) {
            return false;
        }

        return send(user, preferences, nudge, ownerToday);
    }

    /**
     * Whether this account can be written to at all.
     *
     * <p>An unverified address is excluded, and that is a deliberate reversal of an earlier
     * plan to treat never-activated accounts as the biggest opportunity. They may well be —
     * but the address was never confirmed, so sending anything beyond the verification mail
     * itself risks landing engagement content in a mailbox that never agreed to exist. That
     * cohort already has a repair path built for it: the resend endpoint from V23. It is
     * transactional, needs no preference, and is the honest thing to send somebody whose
     * address is still unproven.
     */
    private boolean isReachable(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank() && user.isEmailVerified();
    }

    /**
     * Whether the last mail is too recent to send another.
     *
     * <p>Expressed as "today is before last + gap" rather than as a subtraction from today,
     * which is the same sentence read backwards and gets the boundary wrong: with a gap of
     * three, a mail on the 1st has to allow the 4th, and comparing against
     * {@code today.minusDays(3)} suppresses it. The test for this asserts the day the gap
     * expires, not a day safely past it.
     */
    private boolean mailedTooRecently(User user, LocalDate ownerToday) {
        return sendRepository.findLastSentOn(user.getId())
                .filter(last -> ownerToday.isBefore(last.plusDays(minDaysBetween)))
                .isPresent();
    }

    /** Whether the global daily budget still has room. Read once per pass by the caller. */
    @Transactional(readOnly = true)
    public boolean dailyBudgetRemaining(LocalDate day) {
        return sendRepository.countBySentOn(day) < dailyCap;
    }

    /**
     * The decision for one account, with every value it needs loaded against the OWNER's
     * day rather than the server's.
     */
    @Transactional(readOnly = true)
    public Optional<NudgeDecision> decideFor(User user, LocalDate ownerToday) {
        LocalDate windowStart = ownerToday.minusDays(1).minusDays(backfillDays - 1L);
        List<EntityCheckDay> frozenRows = checkDayRepository
                .findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), windowStart, ownerToday.minusDays(1));

        // What a check on the expiring day still earns under THIS account's strategy —
        // GRADUAL, FLAT and TIME_WINDOW pay differently, and a mail quoting the wrong
        // number is worse than one quoting none.
        int remainingXpPercent = (int) Math.round(
                xpDecayCalculator.calculateDecayedXp(100d, user.getXpDecayStrategy(), windowStart, ownerToday));

        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(user.getId());
        boolean scheduledToday = ScheduledOnDayResolver
                .standingOf(CheckDayOwnerType.USER, user.getId(), routines, ownerToday)
                .scheduled();

        return NudgeEligibility.decide(
                ownerToday,
                backfillDays,
                frozenRows,
                remainingXpPercent,
                scheduledToday,
                NudgeEligibility.completedOn(user.getCompletedDays(), ownerToday),
                userStreakService.streakOf(user, ownerToday).currentStreak(),
                user.getMaxConstance(),
                minStreakToDefend,
                recordGap);
    }

    private boolean send(User user, NotificationPreferences preferences, NudgeDecision nudge, LocalDate ownerToday) {
        try {
            emailService.sendEngagementNudge(
                    user.getEmail(), nudge, preferences.getUnsubscribeToken(), user.getLanguageInUse());
        } catch (Exception e) {
            // Swallowed, like the feedback mails: one dead mailbox must not end the pass
            // for everybody after it in the list. The ERROR log is the alert — every ERROR
            // line becomes an event in the error tracker.
            log.error("Engagement nudge {} failed to send for user {}", nudge.kind(), user.getId(), e);
            return false;
        }

        recordSend(user, nudge, ownerToday);
        return true;
    }

    /**
     * REQUIRES_NEW so a failure to record cannot roll back anything else in the pass, and
     * so the constraint violation below is caught against its own transaction rather than
     * poisoning the caller's.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordSend(User user, NudgeDecision nudge, LocalDate ownerToday) {
        try {
            sendRepository.save(new NotificationSend(user, nudge.kind(), ownerToday));
        } catch (DataIntegrityViolationException alreadyRecorded) {
            // Two passes raced on the same account and day. The mail has gone out twice at
            // worst; the row is the same either way, so there is nothing to repair — but it
            // is worth knowing about, because it means the hour gate let two passes overlap.
            log.warn("Duplicate engagement send row for user {} kind {} on {}",
                    user.getId(), nudge.kind(), ownerToday, alreadyRecorded);
        }
    }
}
