package beyou.beyouapp.backend.domain.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.domain.quicknote.QuickNoteColor;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteService;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteCreateRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteResponseDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import jakarta.validation.Validation;

/**
 * The assistant's two Ideas-board tools, called the way the framework calls them: a raw JSON
 * string through the MethodToolCallback, so a binding miss shows up here and not as a wasted
 * model round trip.
 */
@ExtendWith(MockitoExtension.class)
class QuickNoteToolsTest {

    @Mock private QuickNoteService quickNoteService;
    @Mock private UserService userService;

    @InjectMocks private Tools tools;

    private final UUID userId = UUID.randomUUID();
    private final ToolContext toolContext = new ToolContext(Map.of("userId", userId));
    private final User user = new User();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(tools, "validator", Validation.buildDefaultValidatorFactory().getValidator());
        user.setId(userId);
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no tool named " + name));
    }

    private static QuickNoteResponseDTO note(UUID id, String text, QuickNoteColor color) {
        Instant at = Instant.parse("2026-10-17T12:00:00Z");
        return new QuickNoteResponseDTO(id, text, color, 0, 0, 0, null, null, at, at);
    }

    @Test
    void createQuickNoteBindsTextAndAColourInAnyCase() {
        when(userService.findUserById(userId)).thenReturn(user);
        UUID id = UUID.randomUUID();
        when(quickNoteService.create(eq(user), any())).thenReturn(note(id, "learn to juggle", QuickNoteColor.PINK));

        String result = callback("createQuickNote").call("""
                {"text": "learn to juggle", "color": "pink"}
                """, toolContext);

        verify(quickNoteService).create(user, new QuickNoteCreateRequestDTO("learn to juggle", QuickNoteColor.PINK, null, null));
        assertTrue(result.contains(id.toString()), result);
    }

    @Test
    void createQuickNoteWithoutAColourLeavesItToTheService() {
        when(userService.findUserById(userId)).thenReturn(user);
        when(quickNoteService.create(eq(user), any())).thenReturn(note(UUID.randomUUID(), "x", QuickNoteColor.YELLOW));

        callback("createQuickNote").call("""
                {"text": "a podcast about bridges"}
                """, toolContext);

        verify(quickNoteService).create(user, new QuickNoteCreateRequestDTO("a podcast about bridges", null, null, null));
    }

    @Test
    void anUnknownColourNamesTheSixItCouldHaveBeen() {
        when(userService.findUserById(userId)).thenReturn(user);

        RuntimeException e = assertThrows(RuntimeException.class, () -> callback("createQuickNote").call("""
                {"text": "a podcast about bridges", "color": "teal"}
                """, toolContext));

        assertTrue(rootMessage(e).contains("YELLOW, PINK, BLUE, GREEN, PURPLE, ORANGE"), rootMessage(e));
        verify(quickNoteService, never()).create(any(), any());
    }

    @Test
    void aBlankOrOverlongNoteIsRefusedBeforeTheService() {
        when(userService.findUserById(userId)).thenReturn(user);

        assertThrows(RuntimeException.class, () -> callback("createQuickNote").call("""
                {"text": "   "}
                """, toolContext));
        assertThrows(RuntimeException.class, () -> callback("createQuickNote").call(
                "{\"text\": \"" + "n".repeat(501) + "\"}", toolContext));
        verify(quickNoteService, never()).create(any(), any());
    }

    @Test
    void getQuickNotesReturnsIdTextAndColourOnly() {
        when(userService.findUserById(userId)).thenReturn(user);
        UUID id = UUID.randomUUID();
        when(quickNoteService.listActive(user)).thenReturn(List.of(note(id, "learn to juggle", QuickNoteColor.GREEN)));

        String result = callback("getQuickNotes").call("{}", toolContext);

        assertTrue(result.contains(id.toString()), result);
        assertTrue(result.contains("learn to juggle"), result);
        assertTrue(result.contains("GREEN"), result);
        assertTrue(!result.contains("createdAt") && !result.contains("\"x\""), "positions and times stay out: " + result);
    }

    /** The write refreshes the board on the client; the read changes nothing a client shows. */
    @Test
    void onlyTheCreateToolAsksTheClientToRefresh() {
        assertEquals(List.of("quickNotes"), AgentToolDomains.domainsOf("createQuickNote"));
        assertEquals(List.of(), AgentToolDomains.domainsOf("getQuickNotes"));
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
