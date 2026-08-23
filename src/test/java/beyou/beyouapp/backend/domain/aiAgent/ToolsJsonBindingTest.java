package beyou.beyouapp.backend.domain.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import jakarta.validation.Validation;

/**
 * Calls the tools the way the framework does: MethodToolCallback fed a raw JSON
 * string. ToolsUnitTest calls the Java methods with typed arguments, so by
 * construction it can never catch the class of bug where the JSON bind itself is
 * what fails — a weekday in the wrong case, a time without its leading zero, the
 * argument fields sent without their wrapper object. Every such miss costs a real
 * LLM round trip on the free-tier chain, so what this locks in is that the common
 * near-misses bind and the genuine mistakes come back as messages the model can
 * act on.
 */
@ExtendWith(MockitoExtension.class)
public class ToolsJsonBindingTest {

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private DiaryRoutineService diaryRoutineService;

    @InjectMocks
    private Tools tools;

    private final UUID userId = UUID.randomUUID();
    private final ToolContext toolContext = new ToolContext(Map.of("userId", userId));

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(tools, "validator",
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(
                        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no tool named " + name));
    }

    /** Every message in the cause chain, so assertions survive framework wrapping. */
    private String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            sb.append(cur.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    void scheduleDaysBindInAnyCaseAndInPortuguese() {
        Schedule saved = new Schedule();
        saved.setId(UUID.randomUUID());
        saved.setDays(Set.of(WeekDay.Monday, WeekDay.Saturday, WeekDay.Tuesday));
        when(scheduleService.create(any(), eq(userId))).thenReturn(saved);

        UUID routineId = UUID.randomUUID();
        String json = """
                {"schedule": {"days": ["MONDAY", "sábado", "terça-feira"], "routineId": "%s"}}
                """.formatted(routineId);
        callback("createUserSchedule").call(json, toolContext);

        ArgumentCaptor<CreateScheduleDTO> captor = ArgumentCaptor.forClass(CreateScheduleDTO.class);
        verify(scheduleService).create(captor.capture(), eq(userId));
        assertEquals(Set.of(WeekDay.Monday, WeekDay.Saturday, WeekDay.Tuesday),
                captor.getValue().days());
        assertEquals(routineId, captor.getValue().routineId());
    }

    @Test
    void unknownDayNamesTheAcceptedValues() {
        String json = """
                {"schedule": {"days": ["Funday"], "routineId": "%s"}}
                """.formatted(UUID.randomUUID());
        Exception error = assertThrows(Exception.class,
                () -> callback("createUserSchedule").call(json, toolContext));

        assertTrue(messageChain(error).contains("Monday..Sunday"), messageChain(error));
        verify(scheduleService, never()).create(any(), any());
    }

    @Test
    void fieldsSentWithoutTheWrapperObjectGetAnActionableMessage() {
        // The model flattens the arguments; the DTO parameter binds null and the
        // validator's raw HV000116 for a null object teaches it nothing.
        String json = """
                {"days": ["Monday"], "routineId": "%s"}
                """.formatted(UUID.randomUUID());
        Exception error = assertThrows(Exception.class,
                () -> callback("createUserSchedule").call(json, toolContext));

        assertTrue(messageChain(error).contains("Missing tool arguments"), messageChain(error));
        verify(scheduleService, never()).create(any(), any());
    }

    @Test
    void addHabitAcceptsATimeWithoutTheLeadingZero() {
        UUID routineId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID habitId = UUID.randomUUID();
        String json = """
                {"routineId": "%s", "sectionId": "%s", "habitId": "%s", \
                "startTime": "7:00", "endTime": "07:30"}"""
                .formatted(routineId, sectionId, habitId);
        callback("addHabitToRoutineSection").call(json, toolContext);

        verify(diaryRoutineService).addHabitToSection(eq(routineId), eq(sectionId), eq(habitId),
                eq(LocalTime.of(7, 0)), eq(LocalTime.of(7, 30)), eq(userId));
    }

    @Test
    void garbageTimeNamesTheFieldAndTheFormat() {
        String json = """
                {"routineId": "%s", "sectionId": "%s", "habitId": "%s", \
                "startTime": "half past seven", "endTime": "07:30"}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Exception error = assertThrows(Exception.class,
                () -> callback("addHabitToRoutineSection").call(json, toolContext));

        String chain = messageChain(error);
        assertTrue(chain.contains("startTime") && chain.contains("HH:mm"), chain);
        verify(diaryRoutineService, never()).addHabitToSection(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingTimeNamesTheFieldInsteadOfAnNpe() {
        String json = """
                {"routineId": "%s", "sectionId": "%s", "habitId": "%s", "endTime": "07:30"}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Exception error = assertThrows(Exception.class,
                () -> callback("addHabitToRoutineSection").call(json, toolContext));

        String chain = messageChain(error);
        assertTrue(chain.contains("startTime") && chain.contains("HH:mm"), chain);
        verify(diaryRoutineService, never()).addHabitToSection(any(), any(), any(), any(), any(), any());
    }
}
