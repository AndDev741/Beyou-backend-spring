package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineItemRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.routine.RoutineType;
import beyou.beyouapp.backend.domain.routine.checks.BaseCheck;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.UUID;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds routine structure from request DTOs. Everything it returns is NEW: no caller
 * may hand it an id and expect that row to be reused.
 *
 * <p>It used to reapply the client's section/entry ids, which is how GlitchTip issue 37
 * happened. Both callers put the result into a {@code cascade = ALL} collection, so at
 * flush Hibernate cascades PERSIST onto whatever it finds, and an id that already exists
 * is a detached entity — "detached entity passed to persist", whole request rolled back.
 * Hibernate decides transient-vs-detached from "is the id null", never from a lookup, so
 * an invented id fails the same way as a real one.
 *
 * <p>Neither caller ever wanted those ids. {@code toEntity} builds a brand-new routine,
 * and {@code mapToRoutineSection} only serves the update's NEW-section branch; the
 * existing-section branch never comes through here, it constructs its groups directly.
 * Reusing a row is {@code mergeHabitGroups}/{@code mergeTaskGroups}' job, matched by id
 * against the section that actually owns it.
 *
 * <p>Incoming check lists are dropped for the same reason plus one more: they arrive with
 * no back-reference to their group and {@code habitGroupChecks} is {@code mappedBy}, so
 * persisting them violates {@code habit_group_checks.habit_group_id NOT NULL} — and
 * honouring them would let a caller post {@code checked=true} with an {@code xpGenerated}
 * of its choosing.
 */
@Component
@RequiredArgsConstructor
public class DiaryRoutineMapper {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final TaskService taskService;
    private final HabitService habitService;

    public DiaryRoutine toEntity(DiaryRoutineRequestDTO dto, UUID userId) {
        DiaryRoutine diaryRoutine = new DiaryRoutine();
        diaryRoutine.setName(dto.name());
        diaryRoutine.setIconId(dto.iconId());
        diaryRoutine.setRoutineType(dto.type());
        if (dto.isList()) {
            diaryRoutine.setRoutineSections(new ArrayList<>(
                    List.of(buildListSection(dto.items(), diaryRoutine, userId))));
        } else {
            diaryRoutine.setRoutineSections(mapToRoutineSections(dto.routineSections(), diaryRoutine, userId));
        }
        return diaryRoutine;
    }

    /**
     * The one section a LIST routine stores its items in.
     *
     * <p>Named and iconed after the routine itself because {@code routine_sections.name} is
     * NOT NULL and something has to go there, and because that name is what
     * {@code SnapshotStructureSerializer} copies onto every {@code SnapshotCheck.sectionName}
     * — a history row reading "Errands" is at least true, where a placeholder would be noise
     * in the one place these rows are read by a person.
     *
     * <p>Null start and end times, which the schema has always allowed and
     * {@code formatTime} has always rendered as null. That is what makes a list a list.
     */
    public RoutineSection buildListSection(List<RoutineItemRequestDTO> items, DiaryRoutine routine, UUID userId) {
        RoutineSection section = new RoutineSection();
        section.setName(routine.getName());
        section.setIconId(routine.getIconId());
        section.setOrderIndex(0);
        section.setFavorite(false);
        section.setRoutine(routine);
        section.setTaskGroups(new ArrayList<>());
        section.setHabitGroups(new ArrayList<>());

        int position = 0;
        for (RoutineItemRequestDTO item : items == null ? List.<RoutineItemRequestDTO>of() : items) {
            if (item.isHabit()) {
                HabitGroup group = new HabitGroup();
                group.setHabit(habitService.getOwnedHabit(item.habitId(), userId));
                group.setRoutineSection(section);
                group.setOrderIndex(position++);
                group.setHabitGroupChecks(new ArrayList<>());
                section.getHabitGroups().add(group);
            } else {
                TaskGroup group = new TaskGroup();
                group.setTask(taskService.getOwnedTask(item.taskId(), userId));
                group.setRoutineSection(section);
                group.setOrderIndex(position++);
                group.setTaskGroupChecks(new ArrayList<>());
                section.getTaskGroups().add(group);
            }
        }
        return section;
    }

    public DiaryRoutineResponseDTO toResponse(DiaryRoutine entity) {
        List<DiaryRoutineResponseDTO.RoutineSectionResponseDTO> sectionDTOs = entity.getRoutineSections().stream()
                .sorted(java.util.Comparator.comparingInt(RoutineSection::getOrderIndex))
                .map(this::mapSectionToResponse)
                .collect(Collectors.toList());

        return new DiaryRoutineResponseDTO(
                entity.getId(),
                entity.getName(),
                entity.getIconId(),
                entity.getRoutineType(),
                sectionDTOs,
                entity.isList() ? mapListItems(entity) : List.of(),
                mapSchedule(entity.getSchedule()),
                entity.getXpProgress().getXp(),
                entity.getXpProgress().getActualLevelXp(),
                entity.getXpProgress().getNextLevelXp(),
                entity.getXpProgress().getLevel());
    }

    /**
     * A LIST routine's items flattened into the order the user arranged, habits and tasks
     * interleaved.
     *
     * <p>The same rows the section DTOs above already carry, in the shape its clients render.
     * See the note on {@code DiaryRoutineResponseDTO} for why both go out together.
     */
    private List<DiaryRoutineResponseDTO.RoutineItemResponseDTO> mapListItems(DiaryRoutine entity) {
        return entity.listItems().stream().map(group -> {
            boolean habitSide = group instanceof HabitGroup;
            List<BaseCheck> checks = new ArrayList<>(habitSide
                    ? nullSafe(((HabitGroup) group).getHabitGroupChecks())
                    : nullSafe(((TaskGroup) group).getTaskGroupChecks()));
            return new DiaryRoutineResponseDTO.RoutineItemResponseDTO(
                    group.getId(),
                    habitSide
                            ? DiaryRoutineResponseDTO.RoutineItemType.HABIT
                            : DiaryRoutineResponseDTO.RoutineItemType.TASK,
                    habitSide ? ((HabitGroup) group).getHabit().getId() : null,
                    habitSide ? null : ((TaskGroup) group).getTask().getId(),
                    group.getOrderIndex(),
                    checks);
        }).collect(Collectors.toList());
    }

    /**
     * Copies a lazy check collection into a plain list INSIDE the transaction.
     *
     * <p>Same reason the section mapper does it: handing Jackson the Hibernate proxy is how
     * {@code LazyInitializationException} got thrown during serialization once already.
     */
    private <T extends BaseCheck> List<T> nullSafe(List<T> checks) {
        return checks == null ? List.of() : checks;
    }

    private DiaryRoutineResponseDTO.ScheduleResponseDTO mapSchedule(Schedule schedule) {
        if (schedule == null) {
            return null;
        }
        return new DiaryRoutineResponseDTO.ScheduleResponseDTO(schedule.getId(), Set.copyOf(schedule.getDays()));
    }

    public List<RoutineSection> mapToRoutineSections(List<RoutineSectionRequestDTO> dtos, DiaryRoutine diaryRoutine, UUID userId) {
        if (dtos == null) {
            return new ArrayList<>();
        }

        AtomicInteger index = new AtomicInteger(0);
        return dtos.stream().map(dto -> {
            RoutineSection section = new RoutineSection();
            section.setOrderIndex(index.getAndIncrement());
            section.setName(dto.name());
            section.setIconId(dto.iconId());
            section.setStartTime(dto.startTime());
            section.setEndTime(dto.endTime());
            section.setFavorite(dto.favorite());
            section.setRoutine(diaryRoutine);

            section.setTaskGroups(mapTaskGroups(dto.taskGroup(), section, userId));
            section.setHabitGroups(mapHabitGroups(dto.habitGroup(), section, userId));
            return section;
        }).collect(Collectors.toList());
    }

    public RoutineSection mapToRoutineSection(RoutineSectionRequestDTO dto, DiaryRoutine diaryRoutine, UUID userId) {
        if (dto == null) {
            return new RoutineSection();
        }

        RoutineSection section = new RoutineSection();
        section.setName(dto.name());
        section.setIconId(dto.iconId());
        section.setStartTime(dto.startTime());
        section.setEndTime(dto.endTime());
        section.setFavorite(dto.favorite());
        section.setRoutine(diaryRoutine);

        section.setTaskGroups(mapTaskGroups(dto.taskGroup(), section, userId));
        section.setHabitGroups(mapHabitGroups(dto.habitGroup(), section, userId));
        return section;
    }

    private List<TaskGroup> mapTaskGroups(List<TaskGroupDTO> taskGroupDTOs, RoutineSection section, UUID userId) {
        if (taskGroupDTOs == null) {
            return new ArrayList<>();
        }

        return taskGroupDTOs.stream().map(taskDto -> {
            TaskGroup taskGroup = new TaskGroup();
            // Owner-checked: an unchecked lookup here let one account embed another's
            // task in its own routine, after which checking that routine mutated the
            // victim's data and handed it back in the response.
            Task task = taskService.getOwnedTask(taskDto.taskId(), userId);

            taskGroup.setTask(task);
            taskGroup.setStartTime(taskDto.startTime());
            taskGroup.setEndTime(taskDto.endTime());
            taskGroup.setRoutineSection(section);
            // Never null: toResponse maps it straight after the save, and a null list
            // NPE'd there before (DiaryRoutineUpdateOrphanIT covers that regression).
            taskGroup.setTaskGroupChecks(new ArrayList<>());

            return taskGroup;
        }).collect(Collectors.toList());
    }

    private List<HabitGroup> mapHabitGroups(List<HabitGroupDTO> habitGroupDTOs, RoutineSection section, UUID userId) {
        if (habitGroupDTOs == null) {
            return new ArrayList<>();
        }

        return habitGroupDTOs.stream().map(habitDto -> {
            HabitGroup habitGroup = new HabitGroup();
            // Owner-checked, same reason as the task above.
            Habit habit = habitService.getOwnedHabit(habitDto.habitId(), userId);

            habitGroup.setHabit(habit);
            habitGroup.setStartTime(habitDto.startTime());
            habitGroup.setEndTime(habitDto.endTime());
            habitGroup.setRoutineSection(section);
            habitGroup.setHabitGroupChecks(new ArrayList<>());

            return habitGroup;
        }).collect(Collectors.toList());
    }

    private DiaryRoutineResponseDTO.RoutineSectionResponseDTO mapSectionToResponse(RoutineSection section) {
        List<DiaryRoutineResponseDTO.RoutineSectionResponseDTO.TaskGroupResponseDTO> taskGroupDTOs = section
                .getTaskGroups().stream()
                .map(taskGroup -> new DiaryRoutineResponseDTO.RoutineSectionResponseDTO.TaskGroupResponseDTO(
                        taskGroup.getId(),
                        taskGroup.getTask().getId(),
                        formatTime(taskGroup.getStartTime()),
                        formatTime(taskGroup.getEndTime()),
                        taskGroup.getTaskGroupChecks() == null
                                ? new ArrayList<>()
                                : new ArrayList<>(taskGroup.getTaskGroupChecks())))
                .collect(Collectors.toList());

        List<DiaryRoutineResponseDTO.RoutineSectionResponseDTO.HabitGroupResponseDTO> habitGroupDTOs = section
                .getHabitGroups().stream()
                .map(habitGroup -> new DiaryRoutineResponseDTO.RoutineSectionResponseDTO.HabitGroupResponseDTO(
                        habitGroup.getId(),
                        habitGroup.getHabit().getId(),
                        formatTime(habitGroup.getStartTime()),
                        formatTime(habitGroup.getEndTime()),
                        habitGroup.getHabitGroupChecks() == null
                                ? new ArrayList<>()
                                : new ArrayList<>(habitGroup.getHabitGroupChecks())))
                .collect(Collectors.toList());

        return new DiaryRoutineResponseDTO.RoutineSectionResponseDTO(
                section.getId(),
                section.getName(),
                section.getIconId(),
                formatTime(section.getStartTime()),
                formatTime(section.getEndTime()),
                taskGroupDTOs,
                habitGroupDTOs,
                section.getFavorite() != null ? section.getFavorite() : false);
    }

    private String formatTime(LocalTime time) {
        return time != null ? time.format(TIME_FORMATTER) : null;
    }
}
