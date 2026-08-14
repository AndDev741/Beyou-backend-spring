package beyou.beyouapp.backend.unit.routine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver.Standing;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.Task;

/**
 * The predicate two writers share. Its job is to keep the snapshot scheduler and the
 * day-close pass from disagreeing about whether a routine ran on a given day, and to
 * tell "belongs to no routine" apart from "belongs to one that skips this day" — those
 * two produce different permanent outcome rows.
 */
class ScheduledOnDayResolverUnitTest {

    // 2026-08-10 is a Monday, 2026-08-11 a Tuesday, 2026-08-12 a Wednesday.
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 11);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 12);

    private static Schedule scheduleOf(WeekDay... days) {
        Schedule schedule = new Schedule();
        schedule.setDays(Set.of(days));
        return schedule;
    }

    private static DiaryRoutine routine(Schedule schedule) {
        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        routine.setSchedule(schedule);
        routine.setRoutineSections(new ArrayList<>());
        return routine;
    }

    private static void putHabit(DiaryRoutine routine, UUID habitId) {
        Habit habit = new Habit();
        habit.setId(habitId);
        HabitGroup group = new HabitGroup();
        group.setHabit(habit);

        RoutineSection section = new RoutineSection();
        section.setHabitGroups(new ArrayList<>(List.of(group)));
        section.setTaskGroups(new ArrayList<>());
        routine.getRoutineSections().add(section);
    }

    private static void putTask(DiaryRoutine routine, UUID taskId) {
        Task task = new Task();
        task.setId(taskId);
        TaskGroup group = new TaskGroup();
        group.setTask(task);

        RoutineSection section = new RoutineSection();
        section.setTaskGroups(new ArrayList<>(List.of(group)));
        section.setHabitGroups(new ArrayList<>());
        routine.getRoutineSections().add(section);
    }

    @Nested
    class WeekDayMapping {

        @Test
        void mapsEachDateToItsWeekDayConstant() {
            assertEquals(WeekDay.Monday, ScheduledOnDayResolver.weekDayOf(MONDAY));
            assertEquals(WeekDay.Tuesday, ScheduledOnDayResolver.weekDayOf(TUESDAY));
            assertEquals(WeekDay.Wednesday, ScheduledOnDayResolver.weekDayOf(WEDNESDAY));
        }
    }

    @Nested
    class RoutineCoverage {

        @Test
        void coversOnlyTheDaysItsScheduleLists() {
            DiaryRoutine mwf = routine(scheduleOf(WeekDay.Monday, WeekDay.Wednesday, WeekDay.Friday));

            assertTrue(ScheduledOnDayResolver.coversDay(mwf, WEDNESDAY));
            assertFalse(ScheduledOnDayResolver.coversDay(mwf, TUESDAY));
        }

        @Test
        void coversNothingWhenTheScheduleIsAbsent() {
            assertFalse(ScheduledOnDayResolver.coversDay(routine(null), MONDAY));
        }

        @Test
        void coversNothingWhenTheScheduleHasNoDays() {
            Schedule empty = new Schedule();
            empty.setDays(null);

            assertFalse(ScheduledOnDayResolver.coversDay(routine(empty), MONDAY));
        }

        @Test
        void coversNothingForANullRoutine() {
            assertFalse(ScheduledOnDayResolver.coversDay(null, MONDAY));
        }
    }

    @Nested
    class HabitStanding {

        @Test
        void isScheduledOnADayItsRoutineCoversAndNotOnOthers() {
            UUID habitId = UUID.randomUUID();
            DiaryRoutine mwf = routine(scheduleOf(WeekDay.Monday, WeekDay.Wednesday, WeekDay.Friday));
            putHabit(mwf, habitId);
            List<DiaryRoutine> routines = List.of(mwf);

            Standing onWednesday = ScheduledOnDayResolver.standingOf(
                    CheckDayOwnerType.HABIT, habitId, routines, WEDNESDAY);
            Standing onTuesday = ScheduledOnDayResolver.standingOf(
                    CheckDayOwnerType.HABIT, habitId, routines, TUESDAY);

            assertEquals(new Standing(true, true), onWednesday);
            assertEquals(new Standing(true, false), onTuesday,
                    "Tuesday is not in the schedule, but the habit still belongs to a routine");
        }

        @Test
        void isScheduledOnTheUnionOfEveryRoutineItSitsIn() {
            UUID habitId = UUID.randomUUID();
            DiaryRoutine mondayOnly = routine(scheduleOf(WeekDay.Monday));
            DiaryRoutine wednesdayOnly = routine(scheduleOf(WeekDay.Wednesday));
            putHabit(mondayOnly, habitId);
            putHabit(wednesdayOnly, habitId);
            List<DiaryRoutine> routines = List.of(mondayOnly, wednesdayOnly);

            assertTrue(ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.HABIT, habitId, routines, MONDAY).scheduled());
            assertTrue(ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.HABIT, habitId, routines, WEDNESDAY).scheduled());
            assertFalse(ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.HABIT, habitId, routines, TUESDAY).scheduled());
        }

        @Test
        void belongsToARoutineWithNoScheduleButIsNeverScheduled() {
            UUID habitId = UUID.randomUUID();
            DiaryRoutine unscheduled = routine(null);
            putHabit(unscheduled, habitId);

            Standing standing = ScheduledOnDayResolver.standingOf(
                    CheckDayOwnerType.HABIT, habitId, List.of(unscheduled), MONDAY);

            assertEquals(new Standing(true, false), standing,
                    "An unscheduled routine still counts as belonging to a routine");
        }

        @Test
        void isOrphanedWhenNoRoutineHoldsIt() {
            DiaryRoutine someoneElses = routine(scheduleOf(WeekDay.Monday));
            putHabit(someoneElses, UUID.randomUUID());

            Standing standing = ScheduledOnDayResolver.standingOf(
                    CheckDayOwnerType.HABIT, UUID.randomUUID(), List.of(someoneElses), MONDAY);

            assertEquals(Standing.ORPHANED, standing);
        }

        @Test
        void isOrphanedWhenTheRoutineHasNoSections() {
            DiaryRoutine empty = routine(scheduleOf(WeekDay.Monday));

            assertEquals(Standing.ORPHANED, ScheduledOnDayResolver.standingOf(
                    CheckDayOwnerType.HABIT, UUID.randomUUID(), List.of(empty), MONDAY));
        }

        @Test
        void isNotConfusedByATaskSharingTheSameId() {
            UUID sharedId = UUID.randomUUID();
            DiaryRoutine routine = routine(scheduleOf(WeekDay.Monday));
            putTask(routine, sharedId);

            assertEquals(Standing.ORPHANED, ScheduledOnDayResolver.standingOf(
                    CheckDayOwnerType.HABIT, sharedId, List.of(routine), MONDAY),
                    "A task with this id must not answer for a habit with the same id");
        }
    }

    @Nested
    class TaskStanding {

        @Test
        void followsTheSameRulesAsAHabit() {
            UUID taskId = UUID.randomUUID();
            DiaryRoutine mondayOnly = routine(scheduleOf(WeekDay.Monday));
            putTask(mondayOnly, taskId);
            List<DiaryRoutine> routines = List.of(mondayOnly);

            assertEquals(new Standing(true, true), ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.TASK, taskId, routines, MONDAY));
            assertEquals(new Standing(true, false), ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.TASK, taskId, routines, TUESDAY));
        }
    }

    @Nested
    class RoutineStanding {

        @Test
        void answersForItsOwnSchedule() {
            DiaryRoutine mondayOnly = routine(scheduleOf(WeekDay.Monday));
            List<DiaryRoutine> routines = List.of(mondayOnly);

            assertEquals(new Standing(true, true), ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.ROUTINE, mondayOnly.getId(), routines, MONDAY));
            assertEquals(new Standing(true, false), ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.ROUTINE, mondayOnly.getId(), routines, TUESDAY));
        }

        @Test
        void isOrphanedWhenTheIdIsNotInTheList() {
            DiaryRoutine mondayOnly = routine(scheduleOf(WeekDay.Monday));

            assertEquals(Standing.ORPHANED, ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.ROUTINE, UUID.randomUUID(), List.of(mondayOnly), MONDAY));
        }
    }

    @Nested
    class UserStanding {

        @Test
        void isScheduledWhenAnyRoutineCoversTheDay() {
            DiaryRoutine mondayOnly = routine(scheduleOf(WeekDay.Monday));
            DiaryRoutine wednesdayOnly = routine(scheduleOf(WeekDay.Wednesday));
            List<DiaryRoutine> routines = List.of(mondayOnly, wednesdayOnly);

            assertEquals(new Standing(true, true), ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.USER, UUID.randomUUID(), routines, MONDAY));
            assertEquals(new Standing(true, false), ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.USER, UUID.randomUUID(), routines, TUESDAY));
        }

        @Test
        void isOrphanedWithNoRoutinesAtAll() {
            assertEquals(Standing.ORPHANED, ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.USER, UUID.randomUUID(), List.of(), MONDAY));
        }
    }

    @Nested
    class NullHandling {

        @Test
        void isOrphanedForANullOwnerType() {
            DiaryRoutine routine = routine(scheduleOf(WeekDay.Monday));

            assertEquals(Standing.ORPHANED, ScheduledOnDayResolver
                    .standingOf(null, UUID.randomUUID(), List.of(routine), MONDAY));
        }

        @Test
        void isOrphanedForANullRoutineList() {
            assertEquals(Standing.ORPHANED, ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.HABIT, UUID.randomUUID(), null, MONDAY));
        }

        @Test
        void isOrphanedForANullOwnerId() {
            DiaryRoutine routine = routine(scheduleOf(WeekDay.Monday));
            putHabit(routine, UUID.randomUUID());

            assertEquals(Standing.ORPHANED, ScheduledOnDayResolver
                    .standingOf(CheckDayOwnerType.HABIT, null, List.of(routine), MONDAY));
        }
    }
}
