package beyou.beyouapp.backend.integration.routine.snapshot;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotStructureSerializer;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotStructureSerializerTest {

    private SnapshotStructureSerializer serializer;
    private ObjectMapper objectMapper;

    private DiaryRoutine routine;
    private RoutineSnapshot snapshot;

    private UUID habitId;
    private UUID taskId;
    private UUID habitGroupId;
    private UUID taskGroupId;
    private UUID sectionId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        serializer = new SnapshotStructureSerializer(objectMapper);

        habitId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        habitGroupId = UUID.randomUUID();
        taskGroupId = UUID.randomUUID();
        sectionId = UUID.randomUUID();

        // Build Habit
        Habit habit = new Habit();
        habit.setId(habitId);
        habit.setName("Meditate");
        habit.setIconId("icon-meditate");
        habit.setDificulty(3);
        habit.setImportance(5);

        // Build Task
        Task task = new Task();
        task.setId(taskId);
        task.setName("Review PR");
        task.setIconId("icon-review");
        task.setDificulty(4);
        task.setImportance(4);

        // Build HabitGroup
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(habitGroupId);
        habitGroup.setHabit(habit);
        habitGroup.setStartTime(LocalTime.of(6, 0));
        habitGroup.setEndTime(LocalTime.of(6, 30));

        // Build TaskGroup
        TaskGroup taskGroup = new TaskGroup();
        taskGroup.setId(taskGroupId);
        taskGroup.setTask(task);
        taskGroup.setStartTime(LocalTime.of(7, 0));
        taskGroup.setEndTime(LocalTime.of(7, 45));

        // Build RoutineSection
        RoutineSection section = new RoutineSection();
        section.setId(sectionId);
        section.setName("Morning");
        section.setIconId("icon-morning");
        section.setOrderIndex(0);
        section.setStartTime(LocalTime.of(6, 0));
        section.setEndTime(LocalTime.of(8, 0));
        section.setHabitGroups(List.of(habitGroup));
        section.setTaskGroups(List.of(taskGroup));

        // Build DiaryRoutine
        routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        routine.setName("Daily Routine");
        routine.setRoutineSections(List.of(section));

        // Build RoutineSnapshot
        snapshot = new RoutineSnapshot();
        snapshot.setId(UUID.randomUUID());
    }

    @Test
    void serializeStructure_producesValidJsonWithCorrectSectionFields() throws Exception {
        String json = serializer.serializeStructure(routine);

        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.has("sections"));

        JsonNode sections = root.get("sections");
        assertTrue(sections.isArray());
        assertEquals(1, sections.size());

        JsonNode section = sections.get(0);
        assertEquals("Morning", section.get("name").asText());
        assertEquals("icon-morning", section.get("iconId").asText());
        assertEquals(0, section.get("orderIndex").asInt());
        assertEquals("06:00", section.get("startTime").asText());
        assertEquals("08:00", section.get("endTime").asText());
    }

    @Test
    void serializeStructure_containsHabitItemWithCorrectFields() throws Exception {
        String json = serializer.serializeStructure(routine);

        JsonNode items = objectMapper.readTree(json).get("sections").get(0).get("items");
        assertTrue(items.isArray());

        JsonNode habitItem = findItemByType(items, "HABIT");
        assertNotNull(habitItem, "HABIT item should exist");
        assertEquals(habitGroupId.toString(), habitItem.get("groupId").asText());
        assertEquals(habitId.toString(), habitItem.get("itemId").asText());
        assertEquals("Meditate", habitItem.get("name").asText());
        assertEquals("icon-meditate", habitItem.get("iconId").asText());
        assertEquals("06:00", habitItem.get("startTime").asText());
        assertEquals("06:30", habitItem.get("endTime").asText());
    }

    @Test
    void serializeStructure_containsTaskItemWithCorrectFields() throws Exception {
        String json = serializer.serializeStructure(routine);

        JsonNode items = objectMapper.readTree(json).get("sections").get(0).get("items");

        JsonNode taskItem = findItemByType(items, "TASK");
        assertNotNull(taskItem, "TASK item should exist");
        assertEquals(taskGroupId.toString(), taskItem.get("groupId").asText());
        assertEquals(taskId.toString(), taskItem.get("itemId").asText());
        assertEquals("Review PR", taskItem.get("name").asText());
        assertEquals("icon-review", taskItem.get("iconId").asText());
        assertEquals("07:00", taskItem.get("startTime").asText());
        assertEquals("07:45", taskItem.get("endTime").asText());
    }

    @Test
    void serializeStructure_sectionsAreSortedByOrderIndex() throws Exception {
        RoutineSection section2 = new RoutineSection();
        section2.setId(UUID.randomUUID());
        section2.setName("Evening");
        section2.setIconId("icon-evening");
        section2.setOrderIndex(1);
        section2.setStartTime(LocalTime.of(18, 0));
        section2.setEndTime(LocalTime.of(20, 0));
        section2.setHabitGroups(List.of());
        section2.setTaskGroups(List.of());

        RoutineSection section0 = routine.getRoutineSections().get(0);

        // Put them in reverse order to verify sorting
        routine.setRoutineSections(List.of(section2, section0));

        String json = serializer.serializeStructure(routine);
        JsonNode sections = objectMapper.readTree(json).get("sections");

        assertEquals("Morning", sections.get(0).get("name").asText());
        assertEquals("Evening", sections.get(1).get("name").asText());
    }

    @Test
    void serializeStructure_handlesNullTimes() throws Exception {
        RoutineSection sectionNoTimes = new RoutineSection();
        sectionNoTimes.setId(UUID.randomUUID());
        sectionNoTimes.setName("Flexible");
        sectionNoTimes.setIconId("icon-flex");
        sectionNoTimes.setOrderIndex(0);
        sectionNoTimes.setStartTime(null);
        sectionNoTimes.setEndTime(null);
        sectionNoTimes.setHabitGroups(List.of());
        sectionNoTimes.setTaskGroups(List.of());

        routine.setRoutineSections(List.of(sectionNoTimes));

        String json = serializer.serializeStructure(routine);
        JsonNode section = objectMapper.readTree(json).get("sections").get(0);

        assertTrue(section.get("startTime").isNull());
        assertTrue(section.get("endTime").isNull());
    }

    @Test
    void createSnapshotChecks_createsOneCheckPerItem() {
        List<SnapshotCheck> checks = serializer.createSnapshotChecks(routine, snapshot);

        assertEquals(2, checks.size());
    }

    @Test
    void createSnapshotChecks_habitCheckHasCorrectFields() {
        List<SnapshotCheck> checks = serializer.createSnapshotChecks(routine, snapshot);

        SnapshotCheck habitCheck = checks.stream()
            .filter(c -> c.getItemType() == SnapshotItemType.HABIT)
            .findFirst()
            .orElseThrow();

        assertSame(snapshot, habitCheck.getSnapshot());
        assertEquals("Meditate", habitCheck.getItemName());
        assertEquals("icon-meditate", habitCheck.getItemIconId());
        assertEquals("Morning", habitCheck.getSectionName());
        assertEquals(habitId, habitCheck.getOriginalItemId());
        assertEquals(habitGroupId, habitCheck.getOriginalGroupId());
        assertEquals(3, habitCheck.getDifficulty());
        assertEquals(5, habitCheck.getImportance());
        assertFalse(habitCheck.isChecked());
        assertFalse(habitCheck.isSkipped());
        assertEquals(0.0, habitCheck.getXpGenerated(), 0.001);
    }

    @Test
    void createSnapshotChecks_taskCheckHasCorrectFields() {
        List<SnapshotCheck> checks = serializer.createSnapshotChecks(routine, snapshot);

        SnapshotCheck taskCheck = checks.stream()
            .filter(c -> c.getItemType() == SnapshotItemType.TASK)
            .findFirst()
            .orElseThrow();

        assertSame(snapshot, taskCheck.getSnapshot());
        assertEquals("Review PR", taskCheck.getItemName());
        assertEquals("icon-review", taskCheck.getItemIconId());
        assertEquals("Morning", taskCheck.getSectionName());
        assertEquals(taskId, taskCheck.getOriginalItemId());
        assertEquals(taskGroupId, taskCheck.getOriginalGroupId());
        assertEquals(4, taskCheck.getDifficulty());
        assertEquals(4, taskCheck.getImportance());
        assertFalse(taskCheck.isChecked());
        assertFalse(taskCheck.isSkipped());
        assertEquals(0.0, taskCheck.getXpGenerated(), 0.001);
    }

    @Test
    void createSnapshotChecks_withEmptySection_returnsEmptyList() {
        RoutineSection emptySection = new RoutineSection();
        emptySection.setId(UUID.randomUUID());
        emptySection.setName("Empty");
        emptySection.setOrderIndex(0);
        emptySection.setHabitGroups(List.of());
        emptySection.setTaskGroups(List.of());

        routine.setRoutineSections(List.of(emptySection));

        List<SnapshotCheck> checks = serializer.createSnapshotChecks(routine, snapshot);

        assertTrue(checks.isEmpty());
    }

    @Test
    void createSnapshotChecks_withNullGroups_handlesGracefully() {
        RoutineSection sectionWithNulls = new RoutineSection();
        sectionWithNulls.setId(UUID.randomUUID());
        sectionWithNulls.setName("Null Groups");
        sectionWithNulls.setOrderIndex(0);
        sectionWithNulls.setHabitGroups(null);
        sectionWithNulls.setTaskGroups(null);

        routine.setRoutineSections(List.of(sectionWithNulls));

        List<SnapshotCheck> checks = serializer.createSnapshotChecks(routine, snapshot);

        assertTrue(checks.isEmpty());
    }

    private JsonNode findItemByType(JsonNode items, String type) {
        for (JsonNode item : items) {
            if (type.equals(item.get("type").asText())) {
                return item;
            }
        }
        return null;
    }
}
