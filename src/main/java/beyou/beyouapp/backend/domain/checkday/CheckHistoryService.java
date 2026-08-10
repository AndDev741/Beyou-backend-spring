package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.checkday.dto.CheckDayResponseDTO;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * R9/KTD23 — the one read behind {@code GET /check-history}, serving all four owner types.
 *
 * <p>Owner-parameterised rather than one route per resource. A per-resource layout matching
 * the rest of the controllers would have shipped a habit reader and left the task, routine
 * and user rows written but unreadable, and the dashboard's constance widget needs the user
 * rows on day one.
 *
 * <p>Two shapes of leniency run through everything below, and both are deliberate. A range
 * wider than the cap is clamped rather than rejected, because the caller asked for real
 * data and merely asked for too much. An owner id that belongs to somebody else reads back
 * as an all-unknown range rather than a not-owned error, because the query is filtered on
 * the account in a single predicate — there is nothing to deny, and denying would confirm
 * the id exists.
 */
@Service
@RequiredArgsConstructor
public class CheckHistoryService {

    /**
     * The widest range one call will answer, in days, both bounds inclusive. Anything wider
     * is clamped to the most recent {@code MAX_RANGE_DAYS} days rather than refused.
     *
     * <p>A stated constant in the {@code FeedbackService.MAX_PAGE_SIZE} mould, and for the
     * same reason: the bound belongs where the reader of this class can see it, not buried
     * in a comparison. 366 covers a full calendar year including a leap day, which is the
     * longest strip a client has any use for, and — because absent days come back as
     * unknown rather than being omitted (R18) — it is the real bound on the response size.
     * U8's export reuses it.
     */
    public static final int MAX_RANGE_DAYS = 366;

    /**
     * The range returned when the caller names neither end: the last four weeks, ending on
     * the owner's today. Four weeks because the strip it feeds is a week-aligned grid.
     */
    public static final int DEFAULT_RANGE_DAYS = 28;

    private final EntityCheckDayRepository entityCheckDayRepository;

    /**
     * One owner's history over a range, one entry per day, oldest first.
     *
     * @param user      the authenticated account; every row read is filtered to it
     * @param ownerType which kind of thing to read. Required
     * @param ownerId   which one. Null is allowed only for {@link CheckDayOwnerType#USER},
     *                  where it resolves to {@code user} — a client asking for the account's
     *                  own strip should not have to know its own id
     * @param from      first day, inclusive. Null means {@code to} minus
     *                  {@link #DEFAULT_RANGE_DAYS}
     * @param to        last day, inclusive. Null means the owner's today, resolved in the
     *                  owner's zone (R15)
     * @throws BusinessException {@code INVALID_REQUEST} when the owner type is missing, when
     *                  a non-user owner type comes with no id, or when {@code from} falls
     *                  after {@code to}
     */
    @Transactional(readOnly = true)
    public CheckDayResponseDTO history(User user, CheckDayOwnerType ownerType, UUID ownerId,
                                       LocalDate from, LocalDate to) {
        if (user == null) {
            throw new BusinessException(ErrorKey.USER_NOT_FOUND, "No authenticated user");
        }
        if (ownerType == null) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "ownerType is required");
        }

        UUID resolvedOwnerId = resolveOwnerId(user, ownerType, ownerId);

        LocalDate effectiveTo = to != null ? to : UserDateResolver.today(user);
        LocalDate effectiveFrom = from != null
                ? from
                : effectiveTo.minusDays(DEFAULT_RANGE_DAYS - 1L);

        // An inverted range names no days at all, so there is nothing to clamp it toward
        // and an empty answer would read as "this owner has no history" — a wrong answer
        // rather than a refused one. Reachable only from a hand-edited query string.
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "'from' must not be after 'to'");
        }

        effectiveFrom = clampToCap(effectiveFrom, effectiveTo);

        Map<LocalDate, CheckDayOutcome> stored = storedOutcomes(
                user.getId(), ownerType, resolvedOwnerId, effectiveFrom, effectiveTo);

        List<CheckDayResponseDTO.Day> days = new ArrayList<>();
        for (LocalDate day = effectiveFrom; !day.isAfter(effectiveTo); day = day.plusDays(1)) {
            days.add(new CheckDayResponseDTO.Day(day, view(stored.get(day))));
        }

        return new CheckDayResponseDTO(ownerType, resolvedOwnerId, effectiveFrom, effectiveTo, days);
    }

    /**
     * KTD23 — the account is the one owner a client can name without knowing an id, so the
     * user owner type defaults to the caller. Every other type needs one: there is no
     * sensible "the" habit.
     */
    private static UUID resolveOwnerId(User user, CheckDayOwnerType ownerType, UUID ownerId) {
        if (ownerId != null) {
            return ownerId;
        }
        if (ownerType == CheckDayOwnerType.USER) {
            return user.getId();
        }
        throw new BusinessException(ErrorKey.INVALID_REQUEST,
                "ownerId is required for owner type " + ownerType);
    }

    /**
     * Clamps the older end, keeping {@code to} where it is. The recent end is the one
     * anybody asking for a history actually wants, so a caller who asks for five years gets
     * the last year rather than the first.
     */
    private static LocalDate clampToCap(LocalDate from, LocalDate to) {
        long requestedDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (requestedDays <= MAX_RANGE_DAYS) {
            return from;
        }
        return to.minusDays(MAX_RANGE_DAYS - 1L);
    }

    /**
     * The rows that exist, keyed by day. Absences are handled by the caller, which walks
     * every day in the range rather than the rows.
     *
     * <p>{@code uk_entity_check_day_owner_day} makes two rows for one owner-day impossible;
     * if one appeared anyway the later row wins rather than the map throwing, matching
     * {@code CheckProgressCalculator.index}.
     */
    private Map<LocalDate, CheckDayOutcome> storedOutcomes(UUID userId, CheckDayOwnerType ownerType,
                                                           UUID ownerId, LocalDate from, LocalDate to) {
        List<EntityCheckDay> rows = entityCheckDayRepository
                .findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                        userId, ownerType, ownerId, from, to);
        Map<LocalDate, CheckDayOutcome> byDay = new HashMap<>();
        for (EntityCheckDay row : rows) {
            if (row.getDay() != null && row.getOutcome() != null) {
                byDay.put(row.getDay(), row.getOutcome());
            }
        }
        return byDay;
    }

    /**
     * R18 — a day with no row is {@code UNKNOWN} on the wire, never a quiet omission and
     * never a guess at what it would have been.
     */
    private static CheckDayResponseDTO.Outcome view(CheckDayOutcome stored) {
        if (stored == null) {
            return CheckDayResponseDTO.Outcome.UNKNOWN;
        }
        return VIEW_BY_OUTCOME.get(stored);
    }

    /**
     * Explicit pairs rather than {@code valueOf(stored.name())}. The two enums are allowed
     * to diverge — the wire one carries {@code UNKNOWN}, which the database cannot hold —
     * so a name lookup would compile forever and start throwing the first time either side
     * gained a constant the other lacked. A missing pair here fails at boot instead.
     */
    private static final Map<CheckDayOutcome, CheckDayResponseDTO.Outcome> VIEW_BY_OUTCOME =
            buildViewMap();

    private static Map<CheckDayOutcome, CheckDayResponseDTO.Outcome> buildViewMap() {
        Map<CheckDayOutcome, CheckDayResponseDTO.Outcome> map = new EnumMap<>(CheckDayOutcome.class);
        map.put(CheckDayOutcome.DONE, CheckDayResponseDTO.Outcome.DONE);
        map.put(CheckDayOutcome.SKIPPED, CheckDayResponseDTO.Outcome.SKIPPED);
        map.put(CheckDayOutcome.MISSED, CheckDayResponseDTO.Outcome.MISSED);
        map.put(CheckDayOutcome.NOT_SCHEDULED, CheckDayResponseDTO.Outcome.NOT_SCHEDULED);
        map.put(CheckDayOutcome.NOT_IN_ROUTINE, CheckDayResponseDTO.Outcome.NOT_IN_ROUTINE);
        for (CheckDayOutcome outcome : CheckDayOutcome.values()) {
            if (!map.containsKey(outcome)) {
                throw new IllegalStateException(
                        "CheckDayOutcome." + outcome + " has no wire equivalent in "
                                + CheckDayResponseDTO.Outcome.class.getSimpleName());
            }
        }
        return map;
    }
}
