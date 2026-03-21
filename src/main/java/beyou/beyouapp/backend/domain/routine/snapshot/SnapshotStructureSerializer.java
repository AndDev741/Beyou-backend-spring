package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public String serializeStructure(DiaryRoutine routine) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode sectionsNode = root.putArray("sections");

        routine.getRoutineSections().stream()
            .sorted(Comparator.comparingInt(RoutineSection::getOrderIndex))
            .forEach(section -> {
                ObjectNode sectionNode = sectionsNode.addObject();
                sectionNode.put("name", section.getName());
                sectionNode.put("iconId", section.getIconId());
                sectionNode.put("orderIndex", section.getOrderIndex());
                sectionNode.put("startTime", formatTime(section.getStartTime()));
                sectionNode.put("endTime", formatTime(section.getEndTime()));

                ArrayNode itemsNode = sectionNode.putArray("items");

                if (section.getHabitGroups() != null) {
                    section.getHabitGroups().forEach(hg -> {
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
                    section.getTaskGroups().forEach(tg -> {
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
            throw new RuntimeException("Failed to serialize routine structure", e);
        }
    }

    public List<SnapshotCheck> createSnapshotChecks(DiaryRoutine routine, RoutineSnapshot snapshot) {
        List<SnapshotCheck> checks = new ArrayList<>();
        routine.getRoutineSections().stream()
            .sorted(Comparator.comparingInt(RoutineSection::getOrderIndex))
            .forEach(section -> {
                if (section.getHabitGroups() != null) {
                    section.getHabitGroups().forEach(hg -> {
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
                    section.getTaskGroups().forEach(tg -> {
                        SnapshotCheck check = new SnapshotCheck();
                        check.setSnapshot(snapshot);
                        check.setItemType(SnapshotItemType.TASK);
                        check.setItemName(tg.getTask().getName());
                        check.setItemIconId(tg.getTask().getIconId());
                        check.setSectionName(section.getName());
                        check.setOriginalItemId(tg.getTask().getId());
                        check.setOriginalGroupId(tg.getId());
                        check.setDifficulty(tg.getTask().getDificulty());
                        check.setImportance(tg.getTask().getImportance());
                        check.setChecked(false);
                        check.setSkipped(false);
                        check.setXpGenerated(0.0);
                        checks.add(check);
                    });
                }
            });
        return checks;
    }

    private String formatTime(LocalTime time) {
        return time != null ? time.format(TIME_FORMATTER) : null;
    }
}
