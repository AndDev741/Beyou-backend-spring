package beyou.beyouapp.backend.domain.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.CreateMicroTaskRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusDayResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.ReorderMicroTasksRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import jakarta.validation.Validation;

/**
 * The Focus Mode tools.
 *
 * <p>Two things are worth locking in beyond "the call reaches the service". The first is that a
 * missing itemGroupId is REFUSED: micro-tasks land on a routine entry, writing to the wrong one is
 * silent, and the person only finds the stray row later with no way to explain it. The second is
 * that nothing here can write a timer cycle — a cycle is the record that somebody actually sat
 * through one, so an agent able to file them could invent history the user never lived.
 */
@ExtendWith(MockitoExtension.class)
public class FocusToolsUnitTest {

    @Mock
    private FocusService focusService;

    @Mock
    private UserService userService;

    @InjectMocks
    private Tools tools;

    private final UUID userId = UUID.randomUUID();
    private final UUID itemGroupId = UUID.randomUUID();
    private final UUID microTaskId = UUID.randomUUID();
    private final User user = new User();

    /** On /focus with an entry open, which is what lets "add a step here" resolve. */
    private final ToolContext inFocus = new ToolContext(Map.of(
            "userId", userId, "currentPage", "/focus", "selectedItemGroupId", itemGroupId));

    /** Anywhere else: no entry to fall back on, and no fallback either way. */
    private final ToolContext elsewhere = new ToolContext(Map.of(
            "userId", userId, "currentPage", "/routines"));

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(tools, "validator",
                Validation.buildDefaultValidatorFactory().getValidator());
        user.setId(userId);
    }

    private void withUser() {
        when(userService.findUserById(userId)).thenReturn(user);
    }

    private String message(Executable call) {
        return assertThrows(IllegalArgumentException.class, call::run).getMessage();
    }

    private interface Executable {
        void run();
    }

    // ------------------------------------------------------------- the entry id

    @Test
    void aMissingItemGroupNamesTheEntryOpenInFocusMode() {
        String message = message(() -> tools.addMicroTask(null, "fill the bottle", false, inFocus));

        assertTrue(message.contains("Missing itemGroupId"), message);
        assertTrue(message.contains("habitGroupId"), message);
        assertTrue(message.contains(itemGroupId.toString()),
                "the entry the user is looking at is the one the model most likely meant: " + message);
        verify(focusService, never()).addMicroTask(any(), any());
    }

    @Test
    void aMissingItemGroupOffTheFocusPageJustSaysWhereIdsComeFrom() {
        String message = message(() -> tools.addMicroTask(null, "fill the bottle", false, elsewhere));

        assertTrue(message.contains("getUserRoutines"), message);
        assertTrue(message.contains("never a habit or task id"), message);
    }

    /**
     * The open entry is context, not a default. Passing an entry explicitly must win, or "add it
     * to my evening walk" would silently land on whatever the timer happened to be showing.
     */
    @Test
    void anExplicitEntryBeatsTheOneOpenInFocusMode() {
        withUser();
        UUID other = UUID.randomUUID();

        tools.addMicroTask(other, "stretch", false, inFocus);

        ArgumentCaptor<CreateMicroTaskRequestDTO> captor =
                ArgumentCaptor.forClass(CreateMicroTaskRequestDTO.class);
        verify(focusService).addMicroTask(eq(user), captor.capture());
        assertEquals(other, captor.getValue().itemGroupId());
    }

    // ------------------------------------------------------------- micro-tasks

    @Test
    void addMicroTaskCarriesTheNameAndDefaultsPinnedToFalse() {
        withUser();

        tools.addMicroTask(itemGroupId, "  fill the bottle  ", null, inFocus);

        ArgumentCaptor<CreateMicroTaskRequestDTO> captor =
                ArgumentCaptor.forClass(CreateMicroTaskRequestDTO.class);
        verify(focusService).addMicroTask(eq(user), captor.capture());
        assertEquals("  fill the bottle  ", captor.getValue().name(), "trimming is the service's job");
        assertEquals(false, captor.getValue().pinned(),
                "an omitted pinned must not quietly start keeping the name everywhere");
    }

    @Test
    void addMicroTaskRefusesABlankName() {
        String message = message(() -> tools.addMicroTask(itemGroupId, "   ", false, inFocus));

        assertTrue(message.contains("name"), message);
        verify(focusService, never()).addMicroTask(any(), any());
    }

    @Test
    void addMicroTaskRefusesANameLongerThanTheColumn() {
        String message = message(() -> tools.addMicroTask(itemGroupId, "x".repeat(81), false, inFocus));

        assertTrue(message.contains("name"), message);
        verify(focusService, never()).addMicroTask(any(), any());
    }

    /**
     * Pinning reaches every row carrying the name, so "true or false" is the whole decision. A null
     * bound to false would silently unpin something the user asked to keep.
     */
    @Test
    void pinMicroTaskRefusesAnUnsaidValue() {
        String message = message(() -> tools.pinMicroTask(microTaskId, null, inFocus));

        assertTrue(message.contains("Missing pinned"), message);
        verify(focusService, never()).setPinned(any(), any(), anyBoolean());
    }

    @Test
    void deleteMicroTaskReportsSuccessInTheShapeTheOtherDeletesUse() {
        withUser();

        Map<String, String> result = tools.deleteMicroTask(microTaskId, inFocus);

        verify(focusService).deleteMicroTask(user, microTaskId);
        assertTrue(result.get("success").contains("deleted"), result.toString());
    }

    @Test
    void reorderRefusesAnEmptyList() {
        String message = message(() -> tools.reorderMicroTasks(itemGroupId, List.of(), inFocus));

        assertTrue(message.contains("ids"), message);
        verify(focusService, never()).reorderMicroTasks(any(), any());
    }

    @Test
    void reorderPassesTheWholeListThrough() {
        withUser();
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

        tools.reorderMicroTasks(itemGroupId, ids, inFocus);

        ArgumentCaptor<ReorderMicroTasksRequestDTO> captor =
                ArgumentCaptor.forClass(ReorderMicroTasksRequestDTO.class);
        verify(focusService).reorderMicroTasks(eq(user), captor.capture());
        assertEquals(ids, captor.getValue().ids());
        assertEquals(itemGroupId, captor.getValue().itemGroupId());
    }

    // --------------------------------------------------------------- the day

    /**
     * "How did today go" must mean the user's today. Resolving it against the server's zone files
     * the answer under the wrong day for everybody not sitting in it.
     */
    @Test
    void getFocusDayWithoutADateUsesTheOwnersDay() {
        withUser();
        user.setTimezone("Pacific/Kiritimati");
        when(focusService.getDay(eq(user), any())).thenReturn(new FocusDayResponseDTO(null, List.of(), List.of()));

        tools.getFocusDay(null, inFocus);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(focusService).getDay(eq(user), captor.capture());
        assertEquals(LocalDate.now(ZoneId.of("Pacific/Kiritimati")), captor.getValue());
    }

    @Test
    void getFocusDayTakesAnExplicitDay() {
        withUser();
        when(focusService.getDay(eq(user), any())).thenReturn(new FocusDayResponseDTO(null, List.of(), List.of()));

        tools.getFocusDay("2026-08-30", inFocus);

        verify(focusService).getDay(user, LocalDate.of(2026, 8, 30));
    }

    @Test
    void getFocusDayNamesTheFormatItWants() {
        withUser();

        String message = message(() -> tools.getFocusDay("30/08/2026", inFocus));

        assertTrue(message.contains("YYYY-MM-DD"), message);
        verify(focusService, never()).getDay(any(), any());
    }

    // ------------------------------------------------------------- the surface

    /**
     * The registered surface, asserted by name. A cycle write appearing here later would be a
     * deliberate product decision, not something that slips in with a refactor.
     */
    @Test
    void exposesTheMicroTaskSurfaceAndNoWayToWriteACycle() {
        Set<String> names = Arrays.stream(
                        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
                "getItemMicroTasks", "getFocusDay", "addMicroTask",
                "toggleMicroTask", "pinMicroTask", "deleteMicroTask", "reorderMicroTasks")), names.toString());
        assertTrue(names.stream().noneMatch(name -> name.toLowerCase().contains("cycle")),
                "a cycle is the record that somebody sat through a timer: " + names);
    }

    /** Every write tool tells the client what to refetch, or the Focus page goes stale. */
    @Test
    void everyMicroTaskToolReportsTheFocusDomain() {
        for (String tool : List.of("getItemMicroTasks", "addMicroTask", "toggleMicroTask",
                "pinMicroTask", "deleteMicroTask", "reorderMicroTasks")) {
            assertEquals(List.of("focus"), AgentToolDomains.domainsOf(tool), tool);
        }
        assertEquals(List.of(), AgentToolDomains.domainsOf("getFocusDay"),
                "the day view creates nothing, so there is nothing to refetch");
    }
}
