package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.focus.FocusCycle;
import beyou.beyouapp.backend.domain.focus.FocusCycleRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.HistoryEntryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyHistoryDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * A cycle's history, day by day: what was studied inside its subjects (pomodoros and logged
 * sessions), the concepts understood, and the laps that closed. Read only, and like the rest of
 * the notebook not cached.
 *
 * <p>"Inside its subjects" means on a page whose home chain reaches a subject the cycle holds
 * now, active or not, so an archived cycle still has a history. Each minute is filed under the
 * nearest such subject, the same rule that credited it. Minutes are counted where they happened
 * rather than where the lap put them: a session logged today for last Tuesday shows on Tuesday.
 */
@Service
@RequiredArgsConstructor
public class StudyCycleHistoryService {

    /** A month at most per read. The screen shows a week; a month is the widest view it offers. */
    static final int MAX_DAYS = 31;

    private final StudyCycleService cycleService;
    private final StudyCycleDayRepository dayRepository;
    private final StudyCycleTopicRepository topicRepository;
    private final StudyCycleBlockRepository blockRepository;
    private final StudyCycleLapBlockRepository lapBlockRepository;
    private final StudyCycleLapRepository lapRepository;
    private final StudySessionRepository sessionRepository;
    private final FocusCycleRepository focusCycleRepository;

    @Transactional(readOnly = true)
    public StudyHistoryDTO history(User user, UUID cycleId, LocalDate from, LocalDate to) {
        StudyCycle cycle = cycleService.owned(user, cycleId);
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "from and to are dates, from first, at most " + MAX_DAYS + " days apart");
        }
        NotebookFacts facts = cycleService.facts(user.getId());

        Map<DayOfWeek, Integer> week = new EnumMap<>(DayOfWeek.class);
        dayRepository.findByCycleId(cycle.getId()).forEach(d -> week.put(d.getWeekday(), d.getMinutes()));
        List<UUID> topicIds = topicRepository.findByCycleIdOrderByPositionAsc(cycle.getId()).stream()
                .map(StudyCycleTopic::getTopicId).toList();
        Set<UUID> subjects = new HashSet<>(topicIds);

        Map<LocalDate, DayBuilder> days = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.put(day, new DayBuilder(week.getOrDefault(day.getDayOfWeek(), 0)));
        }

        for (FocusCycle pomodoro : focusCycleRepository.findNotebookPomodoros(user.getId(), from, to)) {
            UUID subject = facts.home().nearest(pomodoro.getNotebookPageId(), subjects);
            if (subject == null) continue;
            days.get(pomodoro.getCycleDate()).add(subject, pomodoro.getMinutes(), HistoryEntryDTO.Focus.of(
                    pomodoro.getStartedAt(), facts.subject(subject), facts.ref(pomodoro.getNotebookPageId()),
                    pomodoro.getMinutes()));
        }
        for (StudySession session : sessionRepository.findRange(user.getId(), from, to)) {
            UUID subject = facts.home().nearest(StudyCycleService.sessionPage(session), subjects);
            if (subject == null) continue;
            days.get(session.getStudiedOn()).add(subject, session.getMinutes(), HistoryEntryDTO.Manual.of(
                    session.getCreatedAt(), session.getId(), facts.subject(subject),
                    facts.ref(session.getPageId()), session.getMinutes(), session.getNote()));
        }

        ZoneId zone = UserDateResolver.zoneOf(user);
        var fromInstant = from.atStartOfDay(zone).toInstant();
        var toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();
        Map<UUID, UUID> blockTopics = new LinkedHashMap<>();
        blockRepository.findByCycleIdOrderByPositionAsc(cycle.getId()).forEach(b -> blockTopics.put(b.getId(), b.getTopicId()));
        for (StudyCycleLapBlock row : lapBlockRepository.findByCycleIdInAndClosedAtGreaterThanEqualAndClosedAtLessThan(
                List.of(cycle.getId()), fromInstant, toInstant)) {
            if (row.getState() != LapBlockState.UNDERSTOOD) continue;
            LocalDate day = row.getClosedAt().atZone(zone).toLocalDate();
            UUID topic = blockTopics.get(row.getBlockId());
            DayBuilder builder = days.get(day);
            if (builder == null) continue;
            builder.entries.add(HistoryEntryDTO.Understood.of(row.getClosedAt(),
                    topic == null ? null : facts.subject(topic), facts.ref(row.getTargetPageId()), row.getMinutes()));
        }
        for (StudyCycleLap lap : lapRepository.findByCycleIdAndClosedAtGreaterThanEqualAndClosedAtLessThan(
                cycle.getId(), fromInstant, toInstant)) {
            DayBuilder builder = days.get(lap.getClosedAt().atZone(zone).toLocalDate());
            if (builder != null) builder.entries.add(HistoryEntryDTO.LapClosed.of(lap.getClosedAt(), lap.getNumber()));
        }

        List<StudyHistoryDTO.Day> out = new ArrayList<>();
        days.forEach((date, builder) -> out.add(builder.build(date)));
        return new StudyHistoryDTO(cycle.getId(), from, to, topicIds.stream().map(facts::subject).toList(), out);
    }

    private static final class DayBuilder {
        final int planned;
        int minutes;
        final Map<UUID, Integer> byTopic = new LinkedHashMap<>();
        final List<HistoryEntryDTO> entries = new ArrayList<>();

        DayBuilder(int planned) {
            this.planned = planned;
        }

        void add(UUID topic, int minutes, HistoryEntryDTO entry) {
            this.minutes += minutes;
            byTopic.merge(topic, minutes, Integer::sum);
            entries.add(entry);
        }

        StudyHistoryDTO.Day build(LocalDate date) {
            entries.sort(Comparator.comparing(HistoryEntryDTO::at, Comparator.nullsLast(Comparator.naturalOrder())));
            return new StudyHistoryDTO.Day(date, planned, minutes,
                    byTopic.entrySet().stream().map(e -> new StudyHistoryDTO.TopicMinutes(e.getKey(), e.getValue())).toList(),
                    List.copyOf(entries));
        }
    }
}
