package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO.RoutineSectionResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO.RoutineSectionResponseDTO.TaskGroupResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO.RoutineSectionResponseDTO.HabitGroupResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiaryRoutineService {

    private final DiaryRoutineRepository diaryRoutineRepository;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private final TaskService taskService;
    private final HabitService habitService;

    @Transactional(readOnly = true)
    public DiaryRoutineResponseDTO getDiaryRoutineById(UUID id) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException(id));
        return mapToResponseDTO(diaryRoutine);
    }

    @Transactional(readOnly = true)
    public List<DiaryRoutineResponseDTO> getAllDiaryRoutines() {
        return diaryRoutineRepository.findAll().stream()
                .map(this::mapToResponseDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public DiaryRoutineResponseDTO createDiaryRoutine(DiaryRoutineRequestDTO dto) {
        validateRequestDTO(dto);
        DiaryRoutine diaryRoutine = mapToEntity(dto);
        DiaryRoutine saved = diaryRoutineRepository.save(diaryRoutine);
        return mapToResponseDTO(saved);
    }

    @Transactional
    public DiaryRoutineResponseDTO updateDiaryRoutine(UUID id, DiaryRoutineRequestDTO dto) {
        validateRequestDTO(dto);
        DiaryRoutine existing = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException(id));

        existing.setName(dto.name());
        existing.setIconId(dto.iconId());
        existing.getRoutineSections().clear();
        List<RoutineSection> newSections = mapToRoutineSections(dto.routineSections(), existing);
        existing.getRoutineSections().addAll(newSections);

        DiaryRoutine updated = diaryRoutineRepository.save(existing);
        return mapToResponseDTO(updated);
    }

    @Transactional
    public void deleteDiaryRoutine(UUID id) {
        if (!diaryRoutineRepository.existsById(id)) {
            throw new DiaryRoutineNotFoundException(id);
        }
        diaryRoutineRepository.deleteById(id);
    }

    private void validateRequestDTO(DiaryRoutineRequestDTO dto) {
        if (dto.name() == null || dto.name().trim().isEmpty()) {
            throw new IllegalArgumentException("DiaryRoutine name cannot be null or empty");
        }
        if (dto.routineSections() == null) {
            throw new IllegalArgumentException("Routine sections cannot be null");
        }
        for (var section : dto.routineSections()) {
            if (section.name() == null || section.name().trim().isEmpty()) {
                throw new IllegalArgumentException("Routine section name cannot be null or empty");
            }
            if (section.starTime() != null && section.endTime() != null
                    && section.endTime().isBefore(section.starTime())) {
                throw new IllegalArgumentException(
                        "End time must be after start time for routine section: " + section.name());
            }
        }
    }

    private DiaryRoutine mapToEntity(DiaryRoutineRequestDTO dto) {
        DiaryRoutine diaryRoutine = new DiaryRoutine();
        diaryRoutine.setName(dto.name());
        diaryRoutine.setIconId(dto.iconId());
        List<RoutineSection> sections = mapToRoutineSections(dto.routineSections(), diaryRoutine);
        diaryRoutine.setRoutineSections(sections);
        return diaryRoutine;
    }

    private List<RoutineSection> mapToRoutineSections(List<RoutineSectionRequestDTO> dtos, DiaryRoutine diaryRoutine) {
        return dtos.stream().map(dto -> {
            RoutineSection section = new RoutineSection();
            section.setName(dto.name());
            section.setIconId(dto.iconId());
            section.setStartTime(dto.starTime());
            section.setEndTime(dto.endTime());
            section.setRoutine(diaryRoutine);

            List<TaskGroup> taskGroups = dto.taskGroup() != null ? dto.taskGroup().stream().map(taskDto -> {
                TaskGroup taskGroup = new TaskGroup();
                Task task = taskService.getTask(taskDto.TaskId());

                taskGroup.setTask(task);
                taskGroup.setStartTime(taskDto.startTime());
                taskGroup.setRoutineSection(section);

                return taskGroup;
            }).collect(Collectors.toList()) : List.of();

            section.setTaskGroups(taskGroups);

            List<HabitGroup> habitGroups = dto.habitGroup() != null ? dto.habitGroup().stream().map(habitDto -> {
                HabitGroup habitGroup = new HabitGroup();
                Habit habit = habitService.getHabit(habitDto.habitId());

                habitGroup.setHabit(habit);
                habitGroup.setStartTime(habitDto.startTime());
                habitGroup.setRoutineSection(section);

                return habitGroup;
            }).collect(Collectors.toList()) : List.of();
            section.setHabitGroups(habitGroups);

            return section;
        }).collect(Collectors.toList());
    }

    private DiaryRoutineResponseDTO mapToResponseDTO(DiaryRoutine entity) {
        List<RoutineSectionResponseDTO> sectionDTOs = entity.getRoutineSections().stream().map(section -> {
            List<TaskGroupResponseDTO> taskGroupDTOs = section.getTaskGroups().stream()
                    .map(taskGroup -> new TaskGroupResponseDTO(
                            taskGroup.getId(),
                            taskGroup.getTask().getId(),
                            taskGroup.getStartTime() != null ? taskGroup.getStartTime().format(TIME_FORMATTER) : null))
                    .collect(Collectors.toList());

            List<HabitGroupResponseDTO> habitGroupDTOs = section.getHabitGroups().stream()
                    .map(habitGroup -> new HabitGroupResponseDTO(
                            habitGroup.getId(),
                            habitGroup.getHabit().getId(),
                            habitGroup.getStartTime() != null ? habitGroup.getStartTime().format(TIME_FORMATTER)
                                    : null))
                    .collect(Collectors.toList());

            return new RoutineSectionResponseDTO(
                    section.getId(),
                    section.getName(),
                    section.getIconId(),
                    section.getStartTime() != null ? section.getStartTime().format(TIME_FORMATTER) : null,
                    section.getEndTime() != null ? section.getEndTime().format(TIME_FORMATTER) : null,
                    taskGroupDTOs,
                    habitGroupDTOs);
        }).collect(Collectors.toList());

        return new DiaryRoutineResponseDTO(
                entity.getId(),
                entity.getName(),
                entity.getIconId(),
                sectionDTOs);
    }

    // TODO: Add methods for gamification, e.g., calculatePoints(UUID routineId) to
    // compute points based on completed tasks/habits
}