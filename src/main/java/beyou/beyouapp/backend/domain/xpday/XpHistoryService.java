package beyou.beyouapp.backend.domain.xpday;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.xpday.dto.XpHistoryResponseDTO;
import beyou.beyouapp.backend.domain.xpday.dto.XpHistoryResponseDTO.OwnerSeries;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * The read side: one user's XP history as a set of dense daily series.
 *
 * <p>Dense is the whole job. The table only holds days something happened, and a chart
 * drawn straight from those rows would put Monday next to Thursday and call it a week.
 * Every series that comes out of here has one entry per day of the window, in order,
 * with zero for the days nothing moved — because on those days nothing did move, and a
 * bar of height zero says that where a missing bar says nothing at all.
 */
@Service
@RequiredArgsConstructor
public class XpHistoryService {

    /**
     * The widest window a client may ask for.
     *
     * <p>The charts want a week. The cap is here so a client cannot turn one request
     * into a scan of an account's whole history: the response is built in memory, one
     * entry per day per owner, so the size is days times entities.
     */
    public static final int MAX_RANGE_DAYS = 90;

    /** What the widgets and the category cards draw when they ask for nothing else. */
    public static final int DEFAULT_RANGE_DAYS = 7;

    private final EntityXpDayRepository repository;

    /**
     * @param days how many days back to include, today inclusive. Clamped to
     *             {@link #MAX_RANGE_DAYS}, and to at least one.
     */
    @Transactional(readOnly = true)
    public XpHistoryResponseDTO history(User user, Integer days) {
        int window = Math.min(Math.max(days == null ? DEFAULT_RANGE_DAYS : days, 1), MAX_RANGE_DAYS);

        // Today in the ACCOUNT's timezone, so the last bar is the day the user believes
        // it is — the same resolution the write side used to file each row.
        LocalDate to = UserDateResolver.today(user);
        LocalDate from = to.minusDays(window - 1L);

        List<EntityXpDay> rows =
                repository.findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), from, to);

        // Keyed by owner, then by day, so filling the gaps below is a lookup rather than
        // a scan per day.
        Map<XpDayOwnerType, Map<UUID, Map<LocalDate, Double>>> byOwner = new LinkedHashMap<>();
        for (EntityXpDay row : rows) {
            byOwner
                    .computeIfAbsent(row.getOwnerType(), type -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getOwnerId(), id -> new LinkedHashMap<>())
                    .merge(row.getDay(), row.getXp(), Double::sum);
        }

        List<LocalDate> windowDays = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            windowDays.add(day);
        }

        List<OwnerSeries> series = new ArrayList<>();
        byOwner.forEach((ownerType, owners) -> owners.forEach((ownerId, byDay) -> {
            List<Double> values = windowDays.stream()
                    .map(day -> byDay.getOrDefault(day, 0d))
                    .toList();
            series.add(new OwnerSeries(ownerType, ownerId, values));
        }));

        return new XpHistoryResponseDTO(from, to, windowDays, series);
    }
}
