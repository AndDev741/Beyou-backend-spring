package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroup;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SnapshotStructureSerializer {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private final ObjectMapper objectMapper;

    /**
     * The routine as the user had it on {@code day}: items limited to other days are left out,
     * and so is a section they leave empty. A snapshot is the record of one day, and Sunday's
     * history listing a Saturday-only item would show it as missed when the user never had it.
     * {@link #createSnapshotChecks} applies the same filter, so the structure and the checks
     * always name the same items.
     */
    public String serializeStructure(DiaryRoutine routine, LocalDate day) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode sectionsNode = root.putArray("sections");

        routine.getRoutineSections().stream()
            .sorted(Comparator.comparingInt(RoutineSection::getOrderIndex))
            .filter(section -> !emptiedOn(section, day))
            .forEach(section -> {
                ObjectNode sectionNode = sectionsNode.addObject();
                sectionNode.put("name", section.getName());
                sectionNode.put("iconId", section.getIconId());
                sectionNode.put("orderIndex", section.getOrderIndex());
                sectionNode.put("startTime", formatTime(section.getStartTime()));
                sectionNode.put("endTime", formatTime(section.getEndTime()));

                ArrayNode itemsNode = sectionNode.putArray("items");

                if (section.getHabitGroups() != null) {
                    section.getHabitGroups().stream().filter(hg -> activeOn(hg, day)).forEach(hg -> {
                        ObjectNode item = itemsNode.addObject();
                        item.put("type", "HABIT");
                        item.put("groupId", hg.getId().toString());
                        item.put("itemId", hg.getHabit().getId().toString());
                        item.put("name", hg.getHabit().getName());
                        item.put("iconId", hg.getHabit().getIconId());
                        item.put("startTime", formatTime(hg.getStartTime()));
                        item.put("endTime", formatTime(hg.getEndTime()));
                    });
                }
                if (section.getTaskGroups() != null) {
                    section.getTaskGroups().stream().filter(tg -> activeOn(tg, day)).forEach(tg -> {
                        ObjectNode item = itemsNode.addObject();
                        item.put("type", "TASK");
                        item.put("groupId", tg.getId().toString());
                        item.put("itemId", tg.getTask().getId().toString());
                        item.put("name", tg.getTask().getName());
                        item.put("iconId", tg.getTask().getIconId());
                        item.put("startTime", formatTime(tg.getStartTime()));
                        item.put("endTime", formatTime(tg.getEndTime()));
                    });
                }
            });

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to serialize routine structure: {}", e.getMessage());
            throw new BusinessException(ErrorKey.UNEXPECTED_ERROR,
                    "Failed to serialize routine structure");
        }
    }

    /**
     * One unchecked row per item that runs on the snapshot's day. Items limited to other days
     * get no row, which is what keeps {@code SnapshotCheckService.recalculateCompleted} able to
     * call a COMPLETE-mode day finished.
     */
    public List<SnapshotCheck> createSnapshotChecks(DiaryRoutine routine, RoutineSnapshot snapshot) {
        LocalDate day = snapshot.getSnapshotDate();
        List<SnapshotCheck> checks = new ArrayList<>();
        routine.getRoutineSections().stream()
            .sorted(Comparator.comparingInt(RoutineSection::getOrderIndex))
            .forEach(section -> {
                if (section.getHabitGroups() != null) {
                    section.getHabitGroups().stream().filter(hg -> activeOn(hg, day)).forEach(hg -> {
                        SnapshotCheck check = new SnapshotCheck();
                        check.setSnapshot(snapshot);
                        check.setItemType(SnapshotItemType.HABIT);
                        check.setItemName(hg.getHabit().getName());
                        check.setItemIconId(hg.getHabit().getIconId());
                        check.setSectionName(section.getName());
                        check.setOriginalItemId(hg.getHabit().getId());
                        check.setOriginalGroupId(hg.getId());
                        check.setDifficulty(hg.getHabit().getDificulty());
                        check.setImportance(hg.getHabit().getImportance());
                        check.setChecked(false);
                        check.setSkipped(false);
                        check.setXpGenerated(0.0);
                        checks.add(check);
                    });
                }
                if (section.getTaskGroups() != null) {
                    section.getTaskGroups().stream().filter(tg -> activeOn(tg, day)).forEach(tg -> {
                        SnapshotCheck check = new SnapshotCheck();
                        check.setSnapshot(snapshot);
                        check.setItemType(SnapshotItemType.TASK);
                        check.setItemName(tg.getTask().getName());
                        check.setItemIconId(tg.getTask().getIconId());
                        check.setSectionName(section.getName());
                        check.setOriginalItemId(tg.getTask().getId());
                        check.setOriginalGroupId(tg.getId());
                        // A task's priority is optional; SnapshotCheck stores it as a
                        // non-null int. Going through the entity's accessors keeps the
                        // default in one place instead of two that can drift.
                        check.setDifficulty(tg.getTask().effectiveDificulty());
                        check.setImportance(tg.getTask().effectiveImportance());
                        check.setChecked(false);
                        check.setSkipped(false);
                        check.setXpGenerated(0.0);
                        checks.add(check);
                    });
                }
            });
        return checks;
    }

    /** Null day: no filter, every item. Only a snapshot built without a date reaches it. */
    private static boolean activeOn(ItemGroup group, LocalDate day) {
        return day == null || ScheduledOnDayResolver.itemActiveOn(group, day);
    }

    /** The section had items and none of them runs on the day. */
    private static boolean emptiedOn(RoutineSection section, LocalDate day) {
        List<ItemGroup> items = new ArrayList<>();
        if (section.getHabitGroups() != null) {
            items.addAll(section.getHabitGroups());
        }
        if (section.getTaskGroups() != null) {
            items.addAll(section.getTaskGroups());
        }
        return !items.isEmpty() && items.stream().noneMatch(item -> activeOn(item, day));
    }

    private String formatTime(LocalTime time) {
        return time != null ? time.format(TIME_FORMATTER) : null;
    }
}
