package beyou.beyouapp.backend.domain.ai;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.ai.dto.DraftHabitItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewCategoryDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewHabitDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewTaskDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftTaskItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.MaterializeRoutineResponseDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists a confirmed draft atomically: new categories → new habits/tasks →
 * routine (delegating to DiaryRoutineService) → optional schedule. Any failure
 * rolls back everything — no orphan habits/categories.
 *
 * The draft is re-validated here because it round-tripped through the browser.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiRoutineConfirmService {

    private final AiDraftValidator validator;
    private final CategoryService categoryService;
    private final HabitService habitService;
    private final TaskService taskService;
    private final DiaryRoutineService diaryRoutineService;
    private final ScheduleService scheduleService;
    private final UserCacheEvictService userCacheEvictService;

    @Transactional
    public DiaryRoutineResponseDTO confirm(RoutineDraftDTO draft, User user) {
        return confirm(draft, user, null);
    }

    /**
     * routineId == null → creates a new routine (+ optional schedule).
     * routineId != null → REPLACES the structure of an existing owned routine
     * (delegates to updateDiaryRoutine; ownership enforced there). Note: the
     * existing schedule is kept untouched in update mode, and replaced sections
     * lose their check history — same as deleting/recreating sections manually.
     */
    @Transactional
    public DiaryRoutineResponseDTO confirm(RoutineDraftDTO draft, User user, UUID routineId) {
        RoutineDraftDTO valid = validator.validateAndSanitize(draft, user.getId(), ErrorKey.AI_DRAFT_INVALID);

        // 1. new categories — resolve tempKeys to real UUIDs
        Map<String, UUID> categoryIdByTempKey = new HashMap<>();
        for (DraftNewCategoryDTO newCategory : valid.newCategories()) {
            Category created = categoryService.createCategoryEntity(
                    new CategoryRequestDTO(newCategory.name(), newCategory.icon(),
                            newCategory.description(), ExperienceLevel.BEGINNER),
                    user);
            categoryIdByTempKey.put(newCategory.tempKey(), created.getId());
        }

        // 2. sections: create new habits/tasks, collect groups
        List<RoutineSectionRequestDTO> sections = new ArrayList<>();
        for (DraftSectionDTO section : valid.sections()) {
            List<HabitGroupDTO> habitGroups = new ArrayList<>();
            for (DraftHabitItemDTO item : section.habits()) {
                UUID habitId = item.existingHabitId() != null
                        ? item.existingHabitId()
                        : habitService.createHabitEntity(
                                toCreateHabitDTO(item.newHabit(), categoryIdByTempKey), user.getId()).getId();
                habitGroups.add(new HabitGroupDTO(null, habitId,
                        parseTime(item.startTime()), parseTime(item.endTime()), null));
            }
            List<TaskGroupDTO> taskGroups = new ArrayList<>();
            for (DraftTaskItemDTO item : section.tasks()) {
                UUID taskId = item.existingTaskId() != null
                        ? item.existingTaskId()
                        : taskService.createTaskEntity(
                                toCreateTaskDTO(item.newTask(), categoryIdByTempKey), user.getId()).getId();
                taskGroups.add(new TaskGroupDTO(null, taskId,
                        parseTime(item.startTime()), parseTime(item.endTime()), null));
            }
            // RoutineSectionRequestDTO order: (id, name, iconId, startTime, endTime, taskGroup, habitGroup, favorite)
            sections.add(new RoutineSectionRequestDTO(null, section.name(), section.iconId(),
                    parseTime(section.startTime()), parseTime(section.endTime()),
                    taskGroups, habitGroups, false));
        }

        // 3. the routine itself (existing validation + mapper + save)
        DiaryRoutineRequestDTO requestDTO = new DiaryRoutineRequestDTO(valid.name(), valid.iconId(), sections);
        DiaryRoutineResponseDTO result;
        if (routineId == null) {
            result = diaryRoutineService.createDiaryRoutine(requestDTO, user);

            // 4. optional schedule (note: ScheduleService.create removes overlapping
            //    days from other routines — existing app semantics, kept on purpose)
            if (valid.scheduleDays() != null && !valid.scheduleDays().isEmpty()) {
                scheduleService.create(new CreateScheduleDTO(valid.scheduleDays(), result.id()), user.getId());
            }
        } else {
            result = diaryRoutineService.updateDiaryRoutine(routineId, requestDTO, user.getId());
        }

        userCacheEvictService.evictAllUserCaches(user.getId());
        log.info("AI routine confirmed ({}): {} sections, {} new categories",
                routineId == null ? "create" : "update", sections.size(), categoryIdByTempKey.size());
        return result;
    }

    /**
     * Materializes a draft WITHOUT creating the routine: new categories, habits
     * and tasks are persisted (atomically) and every item comes back as a plain
     * entity reference shaped for the manual routine form. The user then edits
     * and saves through the normal create/edit flow. newCategoryIds/newHabitIds/
     * newTaskIds let the UI badge what the AI just created.
     */
    @Transactional
    public MaterializeRoutineResponseDTO materialize(RoutineDraftDTO draft, User user) {
        RoutineDraftDTO valid = validator.validateAndSanitize(draft, user.getId(), ErrorKey.AI_DRAFT_INVALID);

        Map<String, UUID> categoryIdByTempKey = new HashMap<>();
        List<UUID> newCategoryIds = new ArrayList<>();
        for (DraftNewCategoryDTO newCategory : valid.newCategories()) {
            Category created = categoryService.createCategoryEntity(
                    new CategoryRequestDTO(newCategory.name(), newCategory.icon(),
                            newCategory.description(), ExperienceLevel.BEGINNER),
                    user);
            categoryIdByTempKey.put(newCategory.tempKey(), created.getId());
            newCategoryIds.add(created.getId());
        }

        List<UUID> newHabitIds = new ArrayList<>();
        List<UUID> newTaskIds = new ArrayList<>();
        List<MaterializeRoutineResponseDTO.SectionDTO> sections = new ArrayList<>();
        for (DraftSectionDTO section : valid.sections()) {
            List<MaterializeRoutineResponseDTO.HabitGroupRefDTO> habitGroup = new ArrayList<>();
            for (DraftHabitItemDTO item : section.habits()) {
                UUID habitId;
                if (item.existingHabitId() != null) {
                    habitId = item.existingHabitId();
                } else {
                    habitId = habitService.createHabitEntity(
                            toCreateHabitDTO(item.newHabit(), categoryIdByTempKey), user.getId()).getId();
                    newHabitIds.add(habitId);
                }
                habitGroup.add(new MaterializeRoutineResponseDTO.HabitGroupRefDTO(
                        habitId, item.startTime(), item.endTime()));
            }
            List<MaterializeRoutineResponseDTO.TaskGroupRefDTO> taskGroup = new ArrayList<>();
            for (DraftTaskItemDTO item : section.tasks()) {
                UUID taskId;
                if (item.existingTaskId() != null) {
                    taskId = item.existingTaskId();
                } else {
                    taskId = taskService.createTaskEntity(
                            toCreateTaskDTO(item.newTask(), categoryIdByTempKey), user.getId()).getId();
                    newTaskIds.add(taskId);
                }
                taskGroup.add(new MaterializeRoutineResponseDTO.TaskGroupRefDTO(
                        taskId, item.startTime(), item.endTime()));
            }
            sections.add(new MaterializeRoutineResponseDTO.SectionDTO(
                    section.name(), section.iconId(), section.startTime(), section.endTime(),
                    habitGroup, taskGroup));
        }

        userCacheEvictService.evictAllUserCaches(user.getId());
        log.info("AI draft materialized: {} new categories, {} new habits, {} new tasks",
                newCategoryIds.size(), newHabitIds.size(), newTaskIds.size());
        return new MaterializeRoutineResponseDTO(valid.name(), valid.iconId(), valid.scheduleDays(),
                sections, newCategoryIds, newHabitIds, newTaskIds);
    }

    private CreateHabitDTO toCreateHabitDTO(DraftNewHabitDTO habit, Map<String, UUID> categoryIdByTempKey) {
        return new CreateHabitDTO(habit.name(), habit.description(), habit.motivationalPhrase(),
                habit.iconId(), habit.importance(), habit.dificulty(),
                resolveCategoryRefs(habit.categoryRefs(), categoryIdByTempKey), ExperienceLevel.BEGINNER);
    }

    private CreateTaskRequestDTO toCreateTaskDTO(DraftNewTaskDTO task, Map<String, UUID> categoryIdByTempKey) {
        return new CreateTaskRequestDTO(task.name(), task.description(), task.iconId(),
                task.importance(), task.difficulty(),
                resolveCategoryRefs(task.categoryRefs(), categoryIdByTempKey), task.oneTimeTask());
    }

    private List<UUID> resolveCategoryRefs(List<String> refs, Map<String, UUID> categoryIdByTempKey) {
        if (refs == null) {
            return List.of();
        }
        return refs.stream()
                .map(ref -> categoryIdByTempKey.containsKey(ref)
                        ? categoryIdByTempKey.get(ref)
                        : UUID.fromString(ref))
                .distinct()
                .toList();
    }

    private LocalTime parseTime(String time) {
        return time == null ? null : LocalTime.parse(time);
    }
}
