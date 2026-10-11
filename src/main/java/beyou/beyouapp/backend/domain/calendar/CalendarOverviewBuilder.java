package beyou.beyouapp.backend.domain.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.calendar.dto.CalendarDayDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarGoalDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarMoodDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarOverviewDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineSource;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;
import beyou.beyouapp.backend.domain.goal.GoalDeadline;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.mood.MoodEntry;
import beyou.beyouapp.backend.domain.mood.MoodEntryRepository;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import lombok.RequiredArgsConstructor;

/**
 * Joins a range of days into what the calendar draws: calendar tasks, the day's routines, goals
 * ending that day, and the mood.
 *
 * <p>On the server so both clients make one request per visible range and cannot drift on the
 * rules below. One query per source for the whole range, never one per day, so a six-week
 * month grid costs the same handful of reads as a single day.
 *
 * <p>The routine rule is the one with a decision in it:
 * <ul>
 *   <li>A day up to today that has its snapshot shows the snapshot: the name the routine had
 *       then, and how it went. Snapshots are written at the owner's midnight for the day that
 *       ended, so in practice that is every past day.</li>
 *   <li>Today before its snapshot, and every future day, shows what the weekday schedule
 *       assigns, with no counts.</li>
 *   <li>A past day with no snapshot shows no routine. The schedule may have changed since, and
 *       drawing today's schedule over an old day would invent history.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class CalendarOverviewBuilder {

    private static final Comparator<CalendarTask> TASK_ORDER = Comparator
            // Timed first, by time; untimed after, in the order they were written down.
            .comparing((CalendarTask task) -> task.getTaskTime() == null)
            .thenComparing(CalendarTask::getTaskTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CalendarTask::getCreatedAt)
            .thenComparing(CalendarTask::getId);

    private static final Comparator<CalendarRoutineDTO> ROUTINE_ORDER = Comparator
            .comparing((CalendarRoutineDTO routine) -> routine.name() == null ? "" : routine.name(),
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(CalendarRoutineDTO::routineId);

    private final CalendarTaskRepository taskRepository;
    private final CalendarTaskMapper taskMapper;
    private final RoutineSnapshotRepository snapshotRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final GoalRepository goalRepository;
    private final MoodEntryRepository moodEntryRepository;

    /**
     * @param today       the owner's today, in their zone: where snapshots stop and the schedule
     *                    takes over
     * @param zone        the owner's timezone, for each task's reminder instant
     * @param defaultTime the owner's default reminder time
     */
    CalendarOverviewDTO build(UUID userId, LocalDate from, LocalDate to, LocalDate today,
                              ZoneId zone, LocalTime defaultTime) {
        Map<LocalDate, List<CalendarTask>> tasksByDay = taskRepository
                .findByUserIdAndTaskDateBetween(userId, from, to).stream()
                .collect(Collectors.groupingBy(CalendarTask::getTaskDate));

        // Snapshots only exist up to today, and the schedule only matters from today on, so a
        // range wholly on one side of today skips the other query.
        Map<LocalDate, List<RoutineSnapshot>> snapshotsByDay = from.isAfter(today)
                ? Map.of()
                : snapshotRepository.findAllByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                                userId, from, to.isAfter(today) ? today : to).stream()
                        .collect(Collectors.groupingBy(RoutineSnapshot::getSnapshotDate));
        List<DiaryRoutine> routines = to.isBefore(today)
                ? List.of()
                : diaryRoutineRepository.findAllByUserId(userId);

        Map<LocalDate, List<GoalDeadline>> goalsByDay = goalRepository
                .findDeadlinesByUserIdAndEndDateBetweenAndArchivedAtIsNull(userId, from, to).stream()
                .collect(Collectors.groupingBy(GoalDeadline::getEndDate));

        Map<LocalDate, MoodEntry> moodByDay = moodEntryRepository
                .findByUserIdAndEntryDateBetweenOrderByEntryDateDesc(userId, from, to).stream()
                .collect(Collectors.toMap(MoodEntry::getEntryDate, Function.identity()));

        List<CalendarDayDTO> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(new CalendarDayDTO(
                    day,
                    tasksOn(tasksByDay.getOrDefault(day, List.of()), zone, defaultTime),
                    routinesOn(day, today, snapshotsByDay.getOrDefault(day, List.of()), routines),
                    goalsOn(goalsByDay.getOrDefault(day, List.of())),
                    moodOn(moodByDay.get(day))));
        }
        return new CalendarOverviewDTO(from, to, today, days);
    }

    private List<CalendarTaskResponseDTO> tasksOn(List<CalendarTask> tasks, ZoneId zone, LocalTime defaultTime) {
        return tasks.stream()
                .sorted(TASK_ORDER)
                .map(task -> taskMapper.toResponseDTO(task, zone, defaultTime))
                .toList();
    }

    private List<CalendarRoutineDTO> routinesOn(LocalDate day, LocalDate today,
                                                List<RoutineSnapshot> recorded, List<DiaryRoutine> routines) {
        if (!recorded.isEmpty() && !day.isAfter(today)) {
            return recorded.stream().map(CalendarOverviewBuilder::fromSnapshot).sorted(ROUTINE_ORDER).toList();
        }
        if (day.isBefore(today)) {
            return List.of();
        }
        return routines.stream()
                .filter(routine -> ScheduledOnDayResolver.coversDay(routine, day))
                .map(routine -> new CalendarRoutineDTO(routine.getId(), routine.getName(), routine.getIconId(),
                        CalendarRoutineSource.SCHEDULE, null, null, false))
                .sorted(ROUTINE_ORDER)
                .toList();
    }

    /** Checked out of everything on the day's record. A skipped item counts as not checked. */
    private static CalendarRoutineDTO fromSnapshot(RoutineSnapshot snapshot) {
        List<SnapshotCheck> checks = snapshot.getChecks() == null ? List.of() : snapshot.getChecks();
        int checked = (int) checks.stream().filter(SnapshotCheck::isChecked).count();
        return new CalendarRoutineDTO(
                snapshot.getRoutine().getId(),
                snapshot.getRoutineName(),
                snapshot.getRoutineIconId(),
                CalendarRoutineSource.SNAPSHOT,
                checks.size(),
                checked,
                snapshot.isCompleted());
    }

    private static List<CalendarGoalDTO> goalsOn(List<GoalDeadline> goals) {
        return goals.stream()
                .sorted(Comparator.comparing(GoalDeadline::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(GoalDeadline::getId))
                .map(goal -> new CalendarGoalDTO(goal.getId(), goal.getName(), goal.getIconId(),
                        Boolean.TRUE.equals(goal.getComplete())))
                .toList();
    }

    private static CalendarMoodDTO moodOn(MoodEntry entry) {
        if (entry == null) {
            return null;
        }
        boolean hasNote = entry.getNote() != null && !entry.getNote().isBlank();
        return new CalendarMoodDTO(entry.getMood(), hasNote);
    }
}
