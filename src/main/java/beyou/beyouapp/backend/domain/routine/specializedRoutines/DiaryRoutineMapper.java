package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DiaryRoutineMapper {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final TaskService taskService;
    private final HabitService habitService;

    public DiaryRoutine toEntity(DiaryRoutineRequestDTO dto) {
        DiaryRoutine diaryRoutine = new DiaryRoutine();
        diaryRoutine.setName(dto.name());
        diaryRoutine.setIconId(dto.iconId());
        diaryRoutine.setRoutineSections(mapToRoutineSections(dto.routineSections(), diaryRoutine));
        return diaryRoutine;
    }

    public void updateEntity(DiaryRoutine target, DiaryRoutineRequestDTO dto) {
        target.setName(dto.name());
        target.setIconId(dto.iconId());
        target.getRoutineSections().clear();
        target.getRoutineSections().addAll(mapToRoutineSections(dto.routineSections(), target));
    }

    public DiaryRoutineResponseDTO toResponse(DiaryRoutine entity) {
        List<DiaryRoutineResponseDTO.RoutineSectionResponseDTO> sectionDTOs = entity.getRoutineSections().stream()
                .map(this::mapSectionToResponse)
                .collect(Collectors.toList());

        return new DiaryRoutineResponseDTO(
                entity.getId(),
                entity.getName(),
                entity.getIconId(),
                sectionDTOs,
                entity.getSchedule(),
                entity.getXpProgress().getXp(),
                entity.getXpProgress().getActualLevelXp(),
                entity.getXpProgress().getNextLevelXp(),
                entity.getXpProgress().getLevel()
            );
    }

    public List<RoutineSection> mapToRoutineSections(List<RoutineSectionRequestDTO> dtos, DiaryRoutine diaryRoutine) {
        if (dtos == null) {
            return new ArrayList<>();
        }

        AtomicInteger index = new AtomicInteger(0);
        return dtos.stream().map(dto -> {
            RoutineSection section = new RoutineSection();
            if (dto.id() != null) {
                section.setId(dto.id());
            }
            section.setOrderIndex(index.getAndIncrement());
            section.setName(dto.name());
            section.setIconId(dto.iconId());
            section.setStartTime(dto.startTime());
            section.setEndTime(dto.endTime());
            section.setFavorite(dto.favorite());
            section.setRoutine(diaryRoutine);

            section.setTaskGroups(mapTaskGroups(dto.taskGroup(), section));
            section.setHabitGroups(mapHabitGroups(dto.habitGroup(), section));
            return section;
        }).collect(Collectors.toList());
    }

    private List<TaskGroup> mapTaskGroups(List<TaskGroupDTO> taskGroupDTOs, RoutineSection section) {
        if (taskGroupDTOs == null) {
            return new ArrayList<>();
        }

        return taskGroupDTOs.stream().map(taskDto -> {
            TaskGroup taskGroup = new TaskGroup();
            Task task = taskService.getTask(taskDto.taskId());

            if (taskDto.id() != null) {
                taskGroup.setId(taskDto.id());
            }
            taskGroup.setTask(task);
            taskGroup.setStartTime(taskDto.startTime());
            taskGroup.setRoutineSection(section);

            List<TaskGroupCheck> taskChecks = new ArrayList<>();
            if (taskDto.taskGroupCheck() != null) {
                taskChecks.addAll(taskDto.taskGroupCheck());
            }
            taskGroup.setTaskGroupChecks(taskChecks);

            return taskGroup;
        }).collect(Collectors.toList());
    }

    private List<HabitGroup> mapHabitGroups(List<HabitGroupDTO> habitGroupDTOs, RoutineSection section) {
        if (habitGroupDTOs == null) {
            return new ArrayList<>();
        }

        return habitGroupDTOs.stream().map(habitDto -> {
            HabitGroup habitGroup = new HabitGroup();
            Habit habit = habitService.getHabit(habitDto.habitId());

            if (habitDto.id() != null) {
                habitGroup.setId(habitDto.id());
            }
            habitGroup.setHabit(habit);
            habitGroup.setStartTime(habitDto.startTime());
            habitGroup.setRoutineSection(section);

            List<HabitGroupCheck> checks = new ArrayList<>();
            if (habitGroup.getHabitGroupChecks() != null) {
                checks.addAll(habitGroup.getHabitGroupChecks());
            }
            if (habitDto.habitGroupCheck() != null) {
                checks.addAll(habitDto.habitGroupCheck());
            }
            habitGroup.setHabitGroupChecks(checks);

            return habitGroup;
        }).collect(Collectors.toList());
    }

    private DiaryRoutineResponseDTO.RoutineSectionResponseDTO mapSectionToResponse(RoutineSection section) {
        List<DiaryRoutineResponseDTO.RoutineSectionResponseDTO.TaskGroupResponseDTO> taskGroupDTOs = section.getTaskGroups().stream()
                .map(taskGroup -> new DiaryRoutineResponseDTO.RoutineSectionResponseDTO.TaskGroupResponseDTO(
                        taskGroup.getId(),
                        taskGroup.getTask().getId(),
                        formatTime(taskGroup.getStartTime()),
                        taskGroup.getTaskGroupChecks()
                ))
                .collect(Collectors.toList());

        List<DiaryRoutineResponseDTO.RoutineSectionResponseDTO.HabitGroupResponseDTO> habitGroupDTOs = section.getHabitGroups().stream()
                .map(habitGroup -> new DiaryRoutineResponseDTO.RoutineSectionResponseDTO.HabitGroupResponseDTO(
                        habitGroup.getId(),
                        habitGroup.getHabit().getId(),
                        formatTime(habitGroup.getStartTime()),
                        habitGroup.getHabitGroupChecks()
                ))
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
