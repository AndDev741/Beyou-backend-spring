package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteColor;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteService;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteCreateRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteEditRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteLayoutRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNotePositionDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteResponseDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteTaskConversionDTO;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/**
 * HTTP binding of the Ideas board routes: paths, status codes, validation, and the JSON shapes
 * both clients were built against. The rules themselves are in {@code QuickNoteServiceIT}.
 */
@AutoConfigureMockMvc(addFilters = false)
class QuickNoteControllerTest extends AbstractIntegrationTest {

    private static final UUID NOTE_ID = UUID.fromString("3c5a8e2f-6b1d-4f7a-9e0c-2d4b6a8c0e1f");
    private static final UUID TASK_ID = UUID.fromString("9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d");
    private static final Instant CREATED = Instant.parse("2026-10-17T12:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private QuickNoteService quickNoteService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    private static QuickNoteResponseDTO note(Instant archivedAt, UUID convertedTaskId) {
        return new QuickNoteResponseDTO(NOTE_ID, "call Ana about the trip", QuickNoteColor.PINK, 224, 0, 3,
                archivedAt, convertedTaskId, CREATED, CREATED.plusSeconds(60));
    }

    @Test
    void createAnswers201WithTheContractShape() throws Exception {
        when(quickNoteService.create(eq(user), any(QuickNoteCreateRequestDTO.class))).thenReturn(note(null, null));

        mockMvc.perform(post("/quick-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"call Ana about the trip\",\"color\":\"PINK\",\"x\":224,\"y\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(NOTE_ID.toString()))
                .andExpect(jsonPath("$.text").value("call Ana about the trip"))
                .andExpect(jsonPath("$.color").value("PINK"))
                .andExpect(jsonPath("$.x").value(224))
                .andExpect(jsonPath("$.y").value(0))
                .andExpect(jsonPath("$.z").value(3))
                .andExpect(jsonPath("$.archivedAt").isEmpty())
                .andExpect(jsonPath("$.convertedTaskId").isEmpty())
                .andExpect(jsonPath("$.createdAt").value("2026-10-17T12:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-17T12:01:00Z"));

        verify(quickNoteService).create(user,
                new QuickNoteCreateRequestDTO("call Ana about the trip", QuickNoteColor.PINK, 224, 0));
    }

    @Test
    void onlyTextIsRequiredOnCreate() throws Exception {
        when(quickNoteService.create(eq(user), any(QuickNoteCreateRequestDTO.class))).thenReturn(note(null, null));

        mockMvc.perform(post("/quick-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"an idea\"}"))
                .andExpect(status().isCreated());

        verify(quickNoteService).create(user, new QuickNoteCreateRequestDTO("an idea", null, null, null));
    }

    @Test
    void invalidCreateBodiesAreRefusedBeforeTheService() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"text\":\"   \"}",
                "{\"text\":\"" + "n".repeat(501) + "\"}",
                "{\"text\":\"an idea\",\"x\":100001,\"y\":0}",
                "{\"text\":\"an idea\",\"x\":0,\"y\":-100001}")) {
            mockMvc.perform(post("/quick-notes").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        }
        mockMvc.perform(post("/quick-notes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"an idea\",\"color\":\"TEAL\"}"))
                .andExpect(status().isBadRequest());
        verify(quickNoteService, never()).create(any(), any());
    }

    @Test
    void textOfExactlyFiveHundredIsAccepted() throws Exception {
        when(quickNoteService.create(eq(user), any(QuickNoteCreateRequestDTO.class))).thenReturn(note(null, null));

        mockMvc.perform(post("/quick-notes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "n".repeat(500) + "\",\"x\":-100000,\"y\":100000}"))
                .andExpect(status().isCreated());
    }

    @Test
    void listsAreArraysOfNotes() throws Exception {
        when(quickNoteService.listActive(user)).thenReturn(List.of(note(null, null)));
        when(quickNoteService.listArchived(user)).thenReturn(List.of(note(CREATED.plusSeconds(120), TASK_ID)));

        mockMvc.perform(get("/quick-notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(NOTE_ID.toString()));
        mockMvc.perform(get("/quick-notes/archived"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].archivedAt").value("2026-10-17T12:02:00Z"))
                .andExpect(jsonPath("$[0].convertedTaskId").value(TASK_ID.toString()));
    }

    @Test
    void editNeedsTextAndColour() throws Exception {
        when(quickNoteService.edit(eq(user), eq(NOTE_ID), any(QuickNoteEditRequestDTO.class))).thenReturn(note(null, null));

        mockMvc.perform(put("/quick-notes/" + NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"call Ana\",\"color\":\"BLUE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NOTE_ID.toString()));
        verify(quickNoteService).edit(user, NOTE_ID, new QuickNoteEditRequestDTO("call Ana", QuickNoteColor.BLUE));

        mockMvc.perform(put("/quick-notes/" + NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"call Ana\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    @Test
    void layoutTakesOneToTwoHundredPositionsAndAnswersTheBoard() throws Exception {
        when(quickNoteService.saveLayout(eq(user), any(QuickNoteLayoutRequestDTO.class)))
                .thenReturn(List.of(note(null, null)));

        mockMvc.perform(put("/quick-notes/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positions\":[{\"id\":\"" + NOTE_ID + "\",\"x\":-40,\"y\":900,\"z\":12}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(NOTE_ID.toString()));
        verify(quickNoteService).saveLayout(user,
                new QuickNoteLayoutRequestDTO(List.of(new QuickNotePositionDTO(NOTE_ID, -40, 900, 12))));

        StringBuilder tooMany = new StringBuilder("{\"positions\":[");
        for (int i = 0; i <= 200; i++) {
            tooMany.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"x\":0,\"y\":0,\"z\":0}");
        }
        tooMany.append("]}");
        for (String body : List.of(
                "{\"positions\":[]}",
                "{}",
                tooMany.toString(),
                "{\"positions\":[{\"id\":\"" + NOTE_ID + "\",\"x\":0,\"y\":0}]}",
                "{\"positions\":[{\"x\":0,\"y\":0,\"z\":0}]}",
                "{\"positions\":[{\"id\":\"" + NOTE_ID + "\",\"x\":100001,\"y\":0,\"z\":0}]}",
                "{\"positions\":[{\"id\":\"" + NOTE_ID + "\",\"x\":0,\"y\":0,\"z\":-1}]}")) {
            mockMvc.perform(put("/quick-notes/layout").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        }
    }

    @Test
    void tidyArchiveRestoreAndDelete() throws Exception {
        when(quickNoteService.tidy(user)).thenReturn(List.of(note(null, null)));
        when(quickNoteService.archive(user, NOTE_ID)).thenReturn(note(CREATED.plusSeconds(120), null));
        when(quickNoteService.restore(user, NOTE_ID)).thenReturn(note(null, null));

        mockMvc.perform(post("/quick-notes/tidy")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].z").value(3));
        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/archive")).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value("2026-10-17T12:02:00Z"));
        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/restore")).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isEmpty());
        mockMvc.perform(delete("/quick-notes/" + NOTE_ID)).andExpect(status().isNoContent());

        verify(quickNoteService).delete(user, NOTE_ID);
    }

    @Test
    void convertAnswers201WithTheNoteAndTheTaskId() throws Exception {
        CreateTaskRequestDTO task = new CreateTaskRequestDTO("call Ana", "about the trip", "lucide:phone",
                null, null, null, true);
        when(quickNoteService.convertToTask(user, NOTE_ID, task))
                .thenReturn(new QuickNoteTaskConversionDTO(note(CREATED.plusSeconds(120), TASK_ID), TASK_ID));

        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"call Ana\",\"description\":\"about the trip\",\"iconId\":\"lucide:phone\",\"oneTimeTask\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.note.id").value(NOTE_ID.toString()))
                .andExpect(jsonPath("$.note.convertedTaskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.note.archivedAt").value("2026-10-17T12:02:00Z"));
    }

    @Test
    void convertValidatesTheTaskLikePostTask() throws Exception {
        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"iconId\":\"lucide:phone\",\"oneTimeTask\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        verify(quickNoteService, never()).convertToTask(any(), any(), any());
    }

    /** Someone else's note: the service's not-found is a 404 with its own key, on every route. */
    @Test
    void notFoundIs404OnEveryRoute() throws Exception {
        BusinessException notFound = new BusinessException(ErrorKey.QUICK_NOTE_NOT_FOUND, "Quick note not found");
        when(quickNoteService.edit(eq(user), eq(NOTE_ID), any())).thenThrow(notFound);
        when(quickNoteService.archive(user, NOTE_ID)).thenThrow(notFound);
        when(quickNoteService.restore(user, NOTE_ID)).thenThrow(notFound);
        doThrow(notFound).when(quickNoteService).delete(user, NOTE_ID);
        when(quickNoteService.convertToTask(eq(user), eq(NOTE_ID), any())).thenThrow(notFound);
        when(quickNoteService.saveLayout(eq(user), any())).thenThrow(notFound);

        mockMvc.perform(put("/quick-notes/" + NOTE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"mine now\",\"color\":\"PINK\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("QUICK_NOTE_NOT_FOUND"));
        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/archive")).andExpect(status().isNotFound());
        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/restore")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/quick-notes/" + NOTE_ID)).andExpect(status().isNotFound());
        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/task").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mine now\",\"iconId\":\"lucide:phone\",\"oneTimeTask\":false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/quick-notes/layout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positions\":[{\"id\":\"" + NOTE_ID + "\",\"x\":0,\"y\":0,\"z\":0}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("QUICK_NOTE_NOT_FOUND"));
    }

    @Test
    void aFullBoardIs409() throws Exception {
        BusinessException full = new BusinessException(ErrorKey.QUICK_NOTE_LIMIT_REACHED, "The board is full");
        when(quickNoteService.create(eq(user), any())).thenThrow(full);
        when(quickNoteService.restore(user, NOTE_ID)).thenThrow(full);

        mockMvc.perform(post("/quick-notes").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"one more\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorKey").value("QUICK_NOTE_LIMIT_REACHED"));
        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorKey").value("QUICK_NOTE_LIMIT_REACHED"));
    }

    @Test
    void aSecondConversionIs409() throws Exception {
        when(quickNoteService.convertToTask(eq(user), eq(NOTE_ID), any()))
                .thenThrow(new BusinessException(ErrorKey.QUICK_NOTE_ALREADY_CONVERTED, "This note is already a task"));

        mockMvc.perform(post("/quick-notes/" + NOTE_ID + "/task").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"again\",\"iconId\":\"lucide:phone\",\"oneTimeTask\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorKey").value("QUICK_NOTE_ALREADY_CONVERTED"));
    }
}
