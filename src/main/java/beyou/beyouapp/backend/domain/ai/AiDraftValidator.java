package beyou.beyouapp.backend.domain.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.ai.dto.DraftHabitItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewCategoryDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewHabitDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewTaskDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftTaskItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The hard boundary between AI output and the domain. Runs on BOTH the
 * generate response (structuralErrorKey = AI_RESPONSE_INVALID) and the
 * confirm request body (structuralErrorKey = AI_DRAFT_INVALID — the draft
 * round-tripped through the browser and is untrusted).
 *
 * Hard failures throw BusinessException; recoverable issues are sanitized
 * silently (icon fallback, level clamping, truncation, duplicate-name → ref).
 * Time rules mirror DiaryRoutineService.validateItemTimeBounds so a draft
 * that passes here never fails later at routine creation.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiDraftValidator {

    private static final int MAX_SECTIONS = 10;
    private static final int MAX_ITEMS_PER_SECTION = 15;
    private static final int TOLERANCE_MINUTES = 5;
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final HabitRepository habitRepository;
    private final TaskRepository taskRepository;
    private final CategoryRepository categoryRepository;

    public RoutineDraftDTO validateAndSanitize(RoutineDraftDTO draft, UUID userId, ErrorKey structuralErrorKey) {
        if (draft == null) {
            throw new BusinessException(structuralErrorKey, "Draft is missing");
        }
        if (draft.name() == null || draft.name().trim().isEmpty()) {
            throw new BusinessException(structuralErrorKey, "Routine name is required");
        }
        if (draft.sections() == null || draft.sections().isEmpty()) {
            throw new BusinessException(structuralErrorKey, "Draft must contain at least one section");
        }
        if (draft.sections().size() > MAX_SECTIONS) {
            throw new BusinessException(structuralErrorKey, "Draft exceeds " + MAX_SECTIONS + " sections");
        }

        List<Habit> habits = habitRepository.findAllByUserId(userId);
        List<Task> tasks = taskRepository.findAllByUserId(userId).orElse(new ArrayList<>());
        List<Category> categories = categoryRepository.findAllByUserId(userId).orElse(new ArrayList<>());

        Set<UUID> ownedHabitIds = new HashSet<>();
        Map<String, UUID> habitIdByName = new HashMap<>();
        for (Habit habit : habits) {
            ownedHabitIds.add(habit.getId());
            habitIdByName.put(normalize(habit.getName()), habit.getId());
        }
        Set<UUID> ownedTaskIds = new HashSet<>();
        Map<String, UUID> taskIdByName = new HashMap<>();
        for (Task task : tasks) {
            ownedTaskIds.add(task.getId());
            taskIdByName.put(normalize(task.getName()), task.getId());
        }
        Set<UUID> ownedCategoryIds = new HashSet<>();
        Map<String, UUID> categoryIdByName = new HashMap<>();
        for (Category category : categories) {
            ownedCategoryIds.add(category.getId());
            categoryIdByName.put(normalize(category.getName()), category.getId());
        }

        // --- new categories: dedupe against existing ones, validate tempKeys ---
        List<DraftNewCategoryDTO> sanitizedNewCategories = new ArrayList<>();
        // tempKey -> existing category UUID, for keys re-pointed to an existing category
        Map<String, UUID> remappedTempKeys = new HashMap<>();
        Set<String> validTempKeys = new HashSet<>();
        Set<String> seenTempKeys = new HashSet<>();
        List<DraftNewCategoryDTO> newCategories = draft.newCategories() == null ? List.of() : draft.newCategories();
        for (DraftNewCategoryDTO category : newCategories) {
            if (category.tempKey() == null || category.tempKey().isBlank()
                    || !seenTempKeys.add(category.tempKey())) {
                throw new BusinessException(structuralErrorKey, "Invalid or duplicate category tempKey");
            }
            if (category.name() == null || category.name().trim().isEmpty()) {
                throw new BusinessException(structuralErrorKey, "New category name is required");
            }
            UUID existingMatch = categoryIdByName.get(normalize(category.name()));
            if (existingMatch != null) {
                remappedTempKeys.put(category.tempKey(), existingMatch);
            } else {
                validTempKeys.add(category.tempKey());
                sanitizedNewCategories.add(new DraftNewCategoryDTO(
                        category.tempKey(),
                        truncate(category.name().trim(), 256),
                        AiIconCatalog.orDefault(category.icon()),
                        truncate(category.description(), 256)));
            }
        }

        // --- sections ---
        List<DraftSectionDTO> sanitizedSections = new ArrayList<>();
        for (DraftSectionDTO section : draft.sections()) {
            if (section.name() == null || section.name().trim().isEmpty()) {
                throw new BusinessException(structuralErrorKey, "Section name is required");
            }
            requireValidTime(section.startTime(), true, structuralErrorKey, "section startTime");
            requireValidTime(section.endTime(), false, structuralErrorKey, "section endTime");

            List<DraftHabitItemDTO> habitItems = section.habits() == null ? List.of() : section.habits();
            List<DraftTaskItemDTO> taskItems = section.tasks() == null ? List.of() : section.tasks();
            if (habitItems.size() + taskItems.size() > MAX_ITEMS_PER_SECTION) {
                throw new BusinessException(structuralErrorKey,
                        "Section exceeds " + MAX_ITEMS_PER_SECTION + " items");
            }

            List<DraftHabitItemDTO> sanitizedHabits = new ArrayList<>();
            for (DraftHabitItemDTO item : habitItems) {
                DraftHabitItemDTO sanitized = sanitizeHabitItem(item, section, ownedHabitIds, habitIdByName,
                        ownedCategoryIds, validTempKeys, remappedTempKeys, structuralErrorKey);
                if (sanitized != null) {
                    sanitizedHabits.add(sanitized);
                }
            }
            List<DraftTaskItemDTO> sanitizedTasks = new ArrayList<>();
            for (DraftTaskItemDTO item : taskItems) {
                DraftTaskItemDTO sanitized = sanitizeTaskItem(item, section, ownedTaskIds, taskIdByName,
                        ownedCategoryIds, validTempKeys, remappedTempKeys, structuralErrorKey);
                if (sanitized != null) {
                    sanitizedTasks.add(sanitized);
                }
            }

            sanitizedSections.add(new DraftSectionDTO(
                    truncate(section.name().trim(), 256),
                    AiIconCatalog.orDefault(section.iconId()),
                    section.startTime(),
                    section.endTime(),
                    sanitizedHabits,
                    sanitizedTasks));
        }

        return new RoutineDraftDTO(
                truncate(draft.name().trim(), 256),
                AiIconCatalog.orDefault(draft.iconId()),
                sanitizedNewCategories,
                sanitizedSections,
                draft.scheduleDays());
    }

    /**
     * Returns the sanitized item, or null when the item is malformed in a
     * recoverable way (no ref AND no new-item payload) — the AI occasionally
     * emits such husks; dropping one item beats rejecting the whole draft.
     * When BOTH are set, the owned ref wins (preserves XP/streak); an unowned
     * ref with a new-item payload falls back to the new item.
     */
    private DraftHabitItemDTO sanitizeHabitItem(DraftHabitItemDTO item, DraftSectionDTO section,
            Set<UUID> ownedHabitIds, Map<String, UUID> habitIdByName, Set<UUID> ownedCategoryIds,
            Set<String> validTempKeys, Map<String, UUID> remappedTempKeys, ErrorKey errorKey) {
        boolean hasRef = item.existingHabitId() != null;
        boolean hasNew = item.newHabit() != null;
        if (!hasRef && !hasNew) {
            log.warn("Dropping malformed AI habit item (no ref, no new payload) in section {}", section.name());
            return null;
        }
        if (hasRef && hasNew) {
            if (ownedHabitIds.contains(item.existingHabitId())) {
                item = new DraftHabitItemDTO(item.existingHabitId(), null, item.startTime(), item.endTime());
                hasNew = false;
            } else {
                item = new DraftHabitItemDTO(null, item.newHabit(), item.startTime(), item.endTime());
                hasRef = false;
            }
        }
        validateItemTimes(item.startTime(), item.endTime(), section, errorKey, "habit");

        if (hasRef) {
            if (!ownedHabitIds.contains(item.existingHabitId())) {
                throw new BusinessException(errorKey, "Referenced habit does not belong to the user");
            }
            return item;
        }

        DraftNewHabitDTO newHabit = item.newHabit();
        if (newHabit.name() == null || newHabit.name().trim().isEmpty()) {
            throw new BusinessException(errorKey, "New habit name is required");
        }
        // duplicate of an existing habit? convert to a ref (preserves XP/streak)
        UUID existingMatch = habitIdByName.get(normalize(newHabit.name()));
        if (existingMatch != null) {
            return new DraftHabitItemDTO(existingMatch, null, item.startTime(), item.endTime());
        }
        List<String> categoryRefs = sanitizeCategoryRefs(newHabit.categoryRefs(), ownedCategoryIds,
                validTempKeys, remappedTempKeys, errorKey);
        DraftNewHabitDTO sanitized = new DraftNewHabitDTO(
                truncate(newHabit.name().trim(), 256),
                truncate(newHabit.description(), 1000),
                truncate(newHabit.motivationalPhrase(), 500),
                AiIconCatalog.orDefault(newHabit.iconId()),
                clampLevel(newHabit.importance()),
                clampLevel(newHabit.dificulty()),
                categoryRefs);
        return new DraftHabitItemDTO(null, sanitized, item.startTime(), item.endTime());
    }

    /** Same recoverable-malformation rules as sanitizeHabitItem — may return null (drop). */
    private DraftTaskItemDTO sanitizeTaskItem(DraftTaskItemDTO item, DraftSectionDTO section,
            Set<UUID> ownedTaskIds, Map<String, UUID> taskIdByName, Set<UUID> ownedCategoryIds,
            Set<String> validTempKeys, Map<String, UUID> remappedTempKeys, ErrorKey errorKey) {
        boolean hasRef = item.existingTaskId() != null;
        boolean hasNew = item.newTask() != null;
        if (!hasRef && !hasNew) {
            log.warn("Dropping malformed AI task item (no ref, no new payload) in section {}", section.name());
            return null;
        }
        if (hasRef && hasNew) {
            if (ownedTaskIds.contains(item.existingTaskId())) {
                item = new DraftTaskItemDTO(item.existingTaskId(), null, item.startTime(), item.endTime());
                hasNew = false;
            } else {
                item = new DraftTaskItemDTO(null, item.newTask(), item.startTime(), item.endTime());
                hasRef = false;
            }
        }
        validateItemTimes(item.startTime(), item.endTime(), section, errorKey, "task");

        if (hasRef) {
            if (!ownedTaskIds.contains(item.existingTaskId())) {
                throw new BusinessException(errorKey, "Referenced task does not belong to the user");
            }
            return item;
        }

        DraftNewTaskDTO newTask = item.newTask();
        if (newTask.name() == null || newTask.name().trim().isEmpty()) {
            throw new BusinessException(errorKey, "New task name is required");
        }
        UUID existingMatch = taskIdByName.get(normalize(newTask.name()));
        if (existingMatch != null) {
            return new DraftTaskItemDTO(existingMatch, null, item.startTime(), item.endTime());
        }
        List<String> categoryRefs = sanitizeCategoryRefs(newTask.categoryRefs(), ownedCategoryIds,
                validTempKeys, remappedTempKeys, errorKey);
        DraftNewTaskDTO sanitized = new DraftNewTaskDTO(
                truncate(newTask.name().trim(), 256),
                truncate(newTask.description(), 1000),
                AiIconCatalog.orDefault(newTask.iconId()),
                clampLevel(newTask.importance()),
                clampLevel(newTask.difficulty()),
                categoryRefs,
                newTask.oneTimeTask());
        return new DraftTaskItemDTO(null, sanitized, item.startTime(), item.endTime());
    }

    /**
     * Each ref must be an owned category UUID or a declared tempKey.
     * tempKeys remapped to an existing category (name match) resolve to that UUID.
     */
    private List<String> sanitizeCategoryRefs(List<String> refs, Set<UUID> ownedCategoryIds,
            Set<String> validTempKeys, Map<String, UUID> remappedTempKeys, ErrorKey errorKey) {
        if (refs == null) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            if (validTempKeys.contains(ref)) {
                sanitized.add(ref);
                continue;
            }
            UUID remapped = remappedTempKeys.get(ref);
            if (remapped != null) {
                sanitized.add(remapped.toString());
                continue;
            }
            UUID parsed = tryParseUuid(ref);
            if (parsed != null && ownedCategoryIds.contains(parsed)) {
                sanitized.add(ref);
                continue;
            }
            throw new BusinessException(errorKey, "Unknown category reference: " + ref);
        }
        return sanitized.stream().distinct().toList();
    }

    // --- time rules (mirror DiaryRoutineService.validateItemTimeBounds, ±5min tolerance) ---

    private void validateItemTimes(String startTime, String endTime, DraftSectionDTO section,
            ErrorKey errorKey, String itemType) {
        requireValidTime(startTime, false, errorKey, itemType + " startTime");
        requireValidTime(endTime, false, errorKey, itemType + " endTime");
        if (startTime == null && endTime == null) {
            return;
        }

        Integer sectionStart = toMinutes(section.startTime());
        Integer sectionEnd = toMinutes(section.endTime());
        Integer itemStart = toMinutes(startTime);
        Integer itemEnd = toMinutes(endTime);
        boolean overnight = sectionStart != null && sectionEnd != null && sectionEnd < sectionStart;

        if (itemStart != null && itemEnd != null && !overnight && itemEnd < itemStart) {
            throw new BusinessException(errorKey,
                    "End time must be after start time for " + itemType + " in section " + section.name());
        }
        if (sectionStart != null && sectionEnd != null) {
            if (itemStart != null && !withinWindow(itemStart, sectionStart, sectionEnd, overnight)) {
                throw new BusinessException(errorKey,
                        "Start time out of section bounds for " + itemType + " in section " + section.name());
            }
            if (itemEnd != null && !withinWindow(itemEnd, sectionStart, sectionEnd, overnight)) {
                throw new BusinessException(errorKey,
                        "End time out of section bounds for " + itemType + " in section " + section.name());
            }
        } else if (sectionStart != null && itemStart != null
                && itemStart < sectionStart - TOLERANCE_MINUTES) {
            throw new BusinessException(errorKey,
                    "Start time out of section bounds for " + itemType + " in section " + section.name());
        }
    }

    private boolean withinWindow(int time, int start, int end, boolean overnight) {
        int min = start - TOLERANCE_MINUTES;
        int max = end + TOLERANCE_MINUTES;
        if (!overnight) {
            return time >= Math.max(0, min) && time <= Math.min(1439, max);
        }
        return time >= Math.max(0, min) || time <= Math.min(1439, max);
    }

    private void requireValidTime(String value, boolean required, ErrorKey errorKey, String label) {
        if (value == null) {
            if (required) {
                throw new BusinessException(errorKey, label + " is required");
            }
            return;
        }
        if (!TIME_PATTERN.matcher(value).matches()) {
            throw new BusinessException(errorKey, label + " must be HH:mm, got: " + value);
        }
    }

    private Integer toMinutes(String time) {
        if (time == null) {
            return null;
        }
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    // --- small helpers ---

    private String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private Integer clampLevel(Integer value) {
        if (value == null) {
            return 3;
        }
        return Math.max(1, Math.min(5, value));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
