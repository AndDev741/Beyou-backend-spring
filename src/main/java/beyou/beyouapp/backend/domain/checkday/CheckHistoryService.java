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

        LocalDate ownerToday = UserDateResolver.today(user);
        LocalDate effectiveTo = to != null ? requireSaneEnd(to, ownerToday) : ownerToday;
        LocalDate effectiveFrom = from != null
                ? floorStart(from, ownerToday)
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
     * How far either end of the range may sit from the owner's today, in years. Anything
     * outside is not a range anybody has history in — the app has never been deployed, so
     * two centuries is already generous by a factor of a hundred.
     */
    private static final int MAX_YEARS_FROM_TODAY = 200;

    /**
     * Bounds {@code to} before anything is derived from it.
     *
     * <p>Order matters, and it is the whole point of this method. Both the default
     * {@code from} ({@code to} minus 27 days) and the day-by-day walk that builds the
     * response do date arithmetic on this value, and {@code LocalDate} throws
     * {@code DateTimeException} rather than saturating when that arithmetic leaves the
     * representable range. {@code to=-999999999-01-01} underflowed the backstep;
     * {@code to=+999999999-12-31} overflowed the walk's {@code plusDays(1)}. Neither is an
     * {@code IllegalArgumentException}, so {@code GlobalExceptionHandler} had no mapping and
     * both answered 500 with two full stack traces logged at ERROR — on an endpoint in the
     * sixty-a-minute tier.
     *
     * <p>Refused rather than clamped, matching the inverted-range guard below: a range
     * merely wider than the cap is a real request for too much data and gets clamped, but a
     * year outside {@code [today - 200y, today + 1d]} is a typo or a probe, and answering it
     * with a silently different range would be a wrong answer rather than a refused one.
     *
     * <p>The upper bound is tomorrow, not today. A client a few hours ahead of the zone this
     * resolves in is entitled to name its own date (R15).
     */
    private static LocalDate requireSaneEnd(LocalDate to, LocalDate ownerToday) {
        if (to.isBefore(ownerToday.minusYears(MAX_YEARS_FROM_TODAY))
                || to.isAfter(ownerToday.plusDays(1))) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "'to' must fall within " + MAX_YEARS_FROM_TODAY + " years of today");
        }
        return to;
    }

    /**
     * Floors {@code from} at the same distance, rather than refusing it.
     *
     * <p>The older end is the lenient one throughout this class, and it earns that here:
     * {@link #clampToCap} already moves any {@code from} older than the cap up to
     * {@code to} minus a year, so flooring changes no answer a caller can currently get —
     * it only keeps an absurd value out of the arithmetic. There is no upper guard to
     * match {@link #requireSaneEnd} because a {@code from} past the upper bound is
     * necessarily after a bounded {@code to}, which the inverted-range check already
     * refuses.
     */
    private static LocalDate floorStart(LocalDate from, LocalDate ownerToday) {
        LocalDate floor = ownerToday.minusYears(MAX_YEARS_FROM_TODAY);
        return from.isBefore(floor) ? floor : from;
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
