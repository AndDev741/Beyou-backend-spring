package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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
import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.GoalPace;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNoteOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionStatus;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainService;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSource;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainVerdict;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAreaDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainInsightDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainMemoryGroupDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainMemorySubjectDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainNoteDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainNoteRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOldNoteRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOldNoteResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainRoutineDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainRoutineItemDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOverviewDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainSourceDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/**
 * HTTP binding of the Second Brain routes: paths, status codes, validation, and the JSON shapes
 * the web page was built against. The rules themselves are in {@code SecondBrainServiceIT}.
 */
@AutoConfigureMockMvc(addFilters = false)
class SecondBrainControllerTest extends AbstractIntegrationTest {

    private static final UUID FACT_ID = UUID.fromString("3c5a8e2f-6b1d-4f7a-9e0c-2d4b6a8c0e1f");
    private static final UUID QUESTION_ID = UUID.fromString("9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d");
    private static final UUID CATEGORY_ID = UUID.fromString("5d7e9f1a-2b3c-4d5e-8f6a-7b8c9d0e1f2a");
    private static final UUID GOAL_ID = UUID.fromString("1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9");
    private static final UUID NOTE_ID = UUID.fromString("7b2c4d6e-8f0a-4b1c-9d2e-3f4a5b6c7d8e");
    private static final Instant CREATED = Instant.parse("2026-11-11T12:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SecondBrainService secondBrainService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    private static SecondBrainFactDTO fact() {
        return new SecondBrainFactDTO(FACT_ID, SecondBrainFactKind.OBJECTIVE, "Run a half marathon by June",
                SecondBrainFactOrigin.USER, CREATED, CREATED.plusSeconds(60));
    }

    private static SecondBrainNoteDTO note() {
        return new SecondBrainNoteDTO(NOTE_ID, "Wants more energy for work", SecondBrainNoteOrigin.ANSWER, "Run",
                CREATED, CREATED.plusSeconds(60));
    }

    private static SecondBrainQuestionDTO question(SecondBrainQuestionStatus status, String answer, Instant answeredAt) {
        return new SecondBrainQuestionDTO(QUESTION_ID, SecondBrainQuestionKind.PROFILE_OBJECTIVES,
                "What are the main things you want out of this year?", status, answer, CREATED, answeredAt);
    }

    private static List<SecondBrainSourceDTO> sources() {
        return List.of(
                new SecondBrainSourceDTO(SecondBrainSource.ROUTINES, true),
                new SecondBrainSourceDTO(SecondBrainSource.GOALS, true),
                new SecondBrainSourceDTO(SecondBrainSource.MOOD, false),
                new SecondBrainSourceDTO(SecondBrainSource.CALENDAR, false),
                new SecondBrainSourceDTO(SecondBrainSource.NOTEBOOK, false),
                new SecondBrainSourceDTO(SecondBrainSource.IDEAS, true));
    }

    // ---- GET /second-brain ----

    @Test
    void overviewAnswersTheContractShape() throws Exception {
        when(secondBrainService.overview(user)).thenReturn(new SecondBrainOverviewDTO(
                new SecondBrainInsightDTO("Your mornings hold.", SecondBrainVerdict.ON_COURSE, CREATED, false),
                List.of(new GoalAhead(GOAL_ID, "Read twelve books", "lucide:book", 4d, 10d, "books",
                        LocalDate.of(2026, 11, 16), 5, 40, 6d, 1.0, 86, GoalPace.BEHIND)),
                new SecondBrainRoutineDTO(72, 64,
                        List.of(new SecondBrainRoutineItemDTO("Read", 5, 1, 3)),
                        List.of(new SecondBrainRoutineItemDTO("Gym", 3, 3, 0))),
                List.of(new SecondBrainAreaDTO(CATEGORY_ID, "Health", "lucide:heart", 12, 9, 75)),
                List.of(question(SecondBrainQuestionStatus.OPEN, null, null)),
                List.of(fact()),
                sources(),
                true,
                List.of(
                        new SecondBrainMemoryGroupDTO(CATEGORY_ID, "Health", "lucide:heart", List.of(
                                new SecondBrainMemorySubjectDTO(SecondBrainSubjectType.CATEGORY, CATEGORY_ID, "Run",
                                        null, SecondBrainSubjectType.HABIT, List.of(note())))),
                        new SecondBrainMemoryGroupDTO(null, null, null, List.of(
                                new SecondBrainMemorySubjectDTO(SecondBrainSubjectType.LOOSE, null, "Evening walk",
                                        null, null, List.of())))),
                List.of("Training for a half marathon.", "Prefers mornings.")));

        mockMvc.perform(get("/second-brain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insight.text").value("Your mornings hold."))
                .andExpect(jsonPath("$.insight.verdict").value("ON_COURSE"))
                .andExpect(jsonPath("$.insight.generatedAt").value("2026-11-11T12:00:00Z"))
                .andExpect(jsonPath("$.insight.stale").value(false))
                .andExpect(jsonPath("$.goalsAhead[0].id").value(GOAL_ID.toString()))
                .andExpect(jsonPath("$.goalsAhead[0].name").value("Read twelve books"))
                .andExpect(jsonPath("$.goalsAhead[0].endDate").value("2026-11-16"))
                .andExpect(jsonPath("$.goalsAhead[0].percentComplete").value(40))
                .andExpect(jsonPath("$.goalsAhead[0].pace").value("BEHIND"))
                .andExpect(jsonPath("$.routine.thisWeekPercent").value(72))
                .andExpect(jsonPath("$.routine.previousWeekPercent").value(64))
                .andExpect(jsonPath("$.routine.slipping[0].name").value("Read"))
                .andExpect(jsonPath("$.routine.slipping[0].asked").value(5))
                .andExpect(jsonPath("$.routine.slipping[0].checked").value(1))
                .andExpect(jsonPath("$.routine.slipping[0].skipped").value(3))
                .andExpect(jsonPath("$.routine.slipping[0].length()").value(4))
                .andExpect(jsonPath("$.routine.steady[0].name").value("Gym"))
                .andExpect(jsonPath("$.areas[0].categoryId").value(CATEGORY_ID.toString()))
                .andExpect(jsonPath("$.areas[0].name").value("Health"))
                .andExpect(jsonPath("$.areas[0].iconId").value("lucide:heart"))
                .andExpect(jsonPath("$.areas[0].asked").value(12))
                .andExpect(jsonPath("$.areas[0].checked").value(9))
                .andExpect(jsonPath("$.areas[0].percent").value(75))
                .andExpect(jsonPath("$.questions[0].id").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.questions[0].kind").value("PROFILE_OBJECTIVES"))
                .andExpect(jsonPath("$.questions[0].status").value("OPEN"))
                .andExpect(jsonPath("$.questions[0].answer").isEmpty())
                .andExpect(jsonPath("$.questions[0].createdAt").value("2026-11-11T12:00:00Z"))
                .andExpect(jsonPath("$.questions[0].answeredAt").isEmpty())
                .andExpect(jsonPath("$.facts[0].id").value(FACT_ID.toString()))
                .andExpect(jsonPath("$.facts[0].kind").value("OBJECTIVE"))
                .andExpect(jsonPath("$.facts[0].origin").value("USER"))
                .andExpect(jsonPath("$.facts[0].updatedAt").value("2026-11-11T12:01:00Z"))
                .andExpect(jsonPath("$.sources.length()").value(6))
                .andExpect(jsonPath("$.sources[0].source").value("ROUTINES"))
                .andExpect(jsonPath("$.sources[0].enabled").value(true))
                .andExpect(jsonPath("$.sources[2].source").value("MOOD"))
                .andExpect(jsonPath("$.sources[2].enabled").value(false))
                .andExpect(jsonPath("$.aiAvailable").value(true))
                .andExpect(jsonPath("$.memory.length()").value(2))
                .andExpect(jsonPath("$.memory[0].categoryId").value(CATEGORY_ID.toString()))
                .andExpect(jsonPath("$.memory[0].categoryName").value("Health"))
                .andExpect(jsonPath("$.memory[0].categoryIconId").value("lucide:heart"))
                .andExpect(jsonPath("$.memory[0].length()").value(4))
                .andExpect(jsonPath("$.memory[0].subjects[0].type").value("CATEGORY"))
                .andExpect(jsonPath("$.memory[0].subjects[0].id").value(CATEGORY_ID.toString()))
                .andExpect(jsonPath("$.memory[0].subjects[0].name").value("Run"))
                .andExpect(jsonPath("$.memory[0].subjects[0].iconId").isEmpty())
                .andExpect(jsonPath("$.memory[0].subjects[0].formerType").value("HABIT"))
                .andExpect(jsonPath("$.memory[0].subjects[0].length()").value(6))
                .andExpect(jsonPath("$.memory[0].subjects[0].notes[0].id").value(NOTE_ID.toString()))
                .andExpect(jsonPath("$.memory[0].subjects[0].notes[0].text").value("Wants more energy for work"))
                .andExpect(jsonPath("$.memory[0].subjects[0].notes[0].origin").value("ANSWER"))
                .andExpect(jsonPath("$.memory[0].subjects[0].notes[0].subjectName").value("Run"))
                .andExpect(jsonPath("$.memory[0].subjects[0].notes[0].createdAt").value("2026-11-11T12:00:00Z"))
                .andExpect(jsonPath("$.memory[0].subjects[0].notes[0].updatedAt").value("2026-11-11T12:01:00Z"))
                .andExpect(jsonPath("$.memory[0].subjects[0].notes[0].length()").value(6))
                .andExpect(jsonPath("$.memory[1].categoryId").isEmpty())
                .andExpect(jsonPath("$.memory[1].subjects[0].type").value("LOOSE"))
                .andExpect(jsonPath("$.memory[1].subjects[0].id").isEmpty())
                .andExpect(jsonPath("$.oldNote[1]").value("Prefers mornings."))
                .andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    void anEmptyPageKeepsEveryKeyWithNullsAndEmptyLists() throws Exception {
        when(secondBrainService.overview(user)).thenReturn(new SecondBrainOverviewDTO(
                SecondBrainInsightDTO.none(), List.of(), null, List.of(), List.of(), List.of(), sources(), false));

        mockMvc.perform(get("/second-brain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insight.text").isEmpty())
                .andExpect(jsonPath("$.insight.generatedAt").isEmpty())
                .andExpect(jsonPath("$.insight.stale").value(true))
                .andExpect(jsonPath("$.goalsAhead").isEmpty())
                .andExpect(jsonPath("$.routine").isEmpty())
                .andExpect(jsonPath("$.areas").isEmpty())
                .andExpect(jsonPath("$.questions").isEmpty())
                .andExpect(jsonPath("$.facts").isEmpty())
                .andExpect(jsonPath("$.aiAvailable").value(false))
                .andExpect(jsonPath("$.memory").isEmpty())
                .andExpect(jsonPath("$.oldNote").isEmpty())
                .andExpect(jsonPath("$.length()").value(10));
    }

    // ---- notes ----

    @Test
    void editingANoteAnswersTheNoteAndDeletingOneIs204() throws Exception {
        when(secondBrainService.editNote(eq(user), eq(NOTE_ID), any(SecondBrainNoteRequestDTO.class))).thenReturn(note());

        mockMvc.perform(put("/second-brain/notes/" + NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Wants more energy for work\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NOTE_ID.toString()))
                .andExpect(jsonPath("$.origin").value("ANSWER"))
                .andExpect(jsonPath("$.length()").value(6));
        verify(secondBrainService).editNote(user, NOTE_ID, new SecondBrainNoteRequestDTO("Wants more energy for work"));

        mockMvc.perform(delete("/second-brain/notes/" + NOTE_ID))
                .andExpect(status().isNoContent());
        verify(secondBrainService).deleteNote(user, NOTE_ID);
    }

    /** The e2e suite sends 501 characters and expects a 400, so the cap is pinned here too. */
    @Test
    void invalidNoteBodiesAreRefusedBeforeTheService() throws Exception {
        mockMvc.perform(put("/second-brain/notes/" + NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "x".repeat(500) + "\"}"))
                .andExpect(status().isOk());
        for (String body : List.of("{}", "{\"text\":\"   \"}", "{\"text\":\"" + "x".repeat(501) + "\"}")) {
            mockMvc.perform(put("/second-brain/notes/" + NOTE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        }
        verify(secondBrainService, times(1)).editNote(any(), any(), any());
    }

    @Test
    void aNoteThatIsNotTheCallersIsA404() throws Exception {
        BusinessException notFound = new BusinessException(ErrorKey.SECOND_BRAIN_NOTE_NOT_FOUND, "Note not found");
        when(secondBrainService.editNote(eq(user), eq(NOTE_ID), any(SecondBrainNoteRequestDTO.class))).thenThrow(notFound);
        doThrow(notFound).when(secondBrainService).deleteNote(user, NOTE_ID);

        mockMvc.perform(put("/second-brain/notes/" + NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"mine now\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_NOTE_NOT_FOUND"));
        mockMvc.perform(delete("/second-brain/notes/" + NOTE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_NOTE_NOT_FOUND"));
    }

    // ---- the old note ----

    @Test
    void keepingAndDiscardingASentenceOfTheOldNote() throws Exception {
        when(secondBrainService.keepOldNoteLine(eq(user), any(SecondBrainOldNoteRequestDTO.class)))
                .thenReturn(new SecondBrainOldNoteResultDTO(List.of("Prefers mornings."), fact()));
        when(secondBrainService.discardOldNoteLine(eq(user), any(SecondBrainOldNoteRequestDTO.class)))
                .thenReturn(new SecondBrainOldNoteResultDTO(List.of(), null));

        mockMvc.perform(post("/second-brain/old-note/keep")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Training for a half marathon.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0]").value("Prefers mornings."))
                .andExpect(jsonPath("$.fact.id").value(FACT_ID.toString()))
                .andExpect(jsonPath("$.length()").value(2));
        verify(secondBrainService).keepOldNoteLine(user, new SecondBrainOldNoteRequestDTO("Training for a half marathon."));

        mockMvc.perform(post("/second-brain/old-note/discard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Prefers mornings.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isEmpty())
                .andExpect(jsonPath("$.fact").isEmpty())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(delete("/second-brain/old-note"))
                .andExpect(status().isNoContent());
        verify(secondBrainService).deleteOldNote(user);
    }

    @Test
    void theOldNoteAnswers404ForASentenceItLacksAnd409WithTheProfileFull() throws Exception {
        when(secondBrainService.keepOldNoteLine(eq(user), any(SecondBrainOldNoteRequestDTO.class)))
                .thenThrow(new BusinessException(ErrorKey.SECOND_BRAIN_FACT_LIMIT_REACHED, "full"));
        when(secondBrainService.discardOldNoteLine(eq(user), any(SecondBrainOldNoteRequestDTO.class)))
                .thenThrow(new BusinessException(ErrorKey.SECOND_BRAIN_OLD_NOTE_LINE_NOT_FOUND, "no such line"));

        mockMvc.perform(post("/second-brain/old-note/keep")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Training for a half marathon.\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_FACT_LIMIT_REACHED"));
        mockMvc.perform(post("/second-brain/old-note/discard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Never said this.\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_OLD_NOTE_LINE_NOT_FOUND"));
        mockMvc.perform(post("/second-brain/old-note/discard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- PUT /second-brain/sources/{source} ----

    @Test
    void togglingASourceAnswersAllSixSwitches() throws Exception {
        when(secondBrainService.setSource(user, SecondBrainSource.MOOD, false)).thenReturn(sources());

        mockMvc.perform(put("/second-brain/sources/MOOD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[2].source").value("MOOD"))
                .andExpect(jsonPath("$[2].enabled").value(false));

        verify(secondBrainService).setSource(user, SecondBrainSource.MOOD, false);
    }

    @Test
    void anUnknownSourceOrABodyWithoutTheFlagIsA400() throws Exception {
        mockMvc.perform(put("/second-brain/sources/JOURNAL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));

        for (String body : List.of("{}", "{\"enabled\":null}")) {
            mockMvc.perform(put("/second-brain/sources/MOOD")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        }

        verify(secondBrainService, never()).setSource(any(), any(), eq(true));
        verify(secondBrainService, never()).setSource(any(), any(), eq(false));
    }

    // ---- facts ----

    @Test
    void creatingAFactAnswers201WithTheFact() throws Exception {
        when(secondBrainService.createFact(eq(user), any(SecondBrainFactRequestDTO.class))).thenReturn(fact());

        mockMvc.perform(post("/second-brain/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"OBJECTIVE\",\"text\":\"Run a half marathon by June\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FACT_ID.toString()))
                .andExpect(jsonPath("$.kind").value("OBJECTIVE"))
                .andExpect(jsonPath("$.text").value("Run a half marathon by June"))
                .andExpect(jsonPath("$.origin").value("USER"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-11T12:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-11T12:01:00Z"))
                .andExpect(jsonPath("$.length()").value(6));

        verify(secondBrainService).createFact(user,
                new SecondBrainFactRequestDTO(SecondBrainFactKind.OBJECTIVE, "Run a half marathon by June"));
    }

    @Test
    void invalidFactBodiesAreRefusedBeforeTheService() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"text\":\"no kind\"}",
                "{\"kind\":\"OBJECTIVE\"}",
                "{\"kind\":\"OBJECTIVE\",\"text\":\"   \"}",
                "{\"kind\":\"OBJECTIVE\",\"text\":\"" + "x".repeat(281) + "\"}",
                "{\"kind\":\"SECRET\",\"text\":\"no such kind\"}")) {
            mockMvc.perform(post("/second-brain/facts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(put("/second-brain/facts/" + FACT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(authenticatedUser, never()).getAuthenticatedUser();
        verifyNoMoreInteractions(secondBrainService);
    }

    @Test
    void aFullProfileIsA409() throws Exception {
        when(secondBrainService.createFact(eq(user), any(SecondBrainFactRequestDTO.class)))
                .thenThrow(new BusinessException(ErrorKey.SECOND_BRAIN_FACT_LIMIT_REACHED, "full"));

        mockMvc.perform(post("/second-brain/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LIKE\",\"text\":\"one too many\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_FACT_LIMIT_REACHED"));
    }

    @Test
    void editingAndDeletingAFact() throws Exception {
        when(secondBrainService.editFact(eq(user), eq(FACT_ID), any(SecondBrainFactRequestDTO.class))).thenReturn(fact());

        mockMvc.perform(put("/second-brain/facts/" + FACT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LIKE\",\"text\":\"Running\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FACT_ID.toString()));
        verify(secondBrainService).editFact(user, FACT_ID, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "Running"));

        mockMvc.perform(delete("/second-brain/facts/" + FACT_ID))
                .andExpect(status().isNoContent());
        verify(secondBrainService).deleteFact(user, FACT_ID);
    }

    @Test
    void aFactThatIsNotTheCallersIsA404() throws Exception {
        BusinessException notFound = new BusinessException(ErrorKey.SECOND_BRAIN_FACT_NOT_FOUND, "Fact not found");
        when(secondBrainService.editFact(eq(user), eq(FACT_ID), any(SecondBrainFactRequestDTO.class))).thenThrow(notFound);
        doThrow(notFound).when(secondBrainService).deleteFact(user, FACT_ID);

        mockMvc.perform(put("/second-brain/facts/" + FACT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LIKE\",\"text\":\"Running\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_FACT_NOT_FOUND"));
        mockMvc.perform(delete("/second-brain/facts/" + FACT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_FACT_NOT_FOUND"));
    }

    // ---- questions ----

    @Test
    void refillAnswersTheOpenQuestions() throws Exception {
        when(secondBrainService.refill(user)).thenReturn(List.of(question(SecondBrainQuestionStatus.OPEN, null, null)));

        mockMvc.perform(post("/second-brain/questions/refill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$[0].kind").value("PROFILE_OBJECTIVES"))
                .andExpect(jsonPath("$[0].text").value("What are the main things you want out of this year?"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].length()").value(7));
    }

    @Test
    void answeringAnswersTheQuestionAndWhatWasLearned() throws Exception {
        when(secondBrainService.answer(eq(user), eq(QUESTION_ID), any(SecondBrainAnswerRequestDTO.class)))
                .thenReturn(new SecondBrainAnswerResultDTO(
                        question(SecondBrainQuestionStatus.ANSWERED, "A half marathon", CREATED.plusSeconds(90)),
                        List.of(fact()), List.of(note())));

        mockMvc.perform(post("/second-brain/questions/" + QUESTION_ID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"A half marathon\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.id").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.question.status").value("ANSWERED"))
                .andExpect(jsonPath("$.question.answer").value("A half marathon"))
                .andExpect(jsonPath("$.question.answeredAt").value("2026-11-11T12:01:30Z"))
                .andExpect(jsonPath("$.learned.length()").value(1))
                .andExpect(jsonPath("$.learned[0].id").value(FACT_ID.toString()))
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0].id").value(NOTE_ID.toString()))
                .andExpect(jsonPath("$.length()").value(3));

        verify(secondBrainService).answer(user, QUESTION_ID, new SecondBrainAnswerRequestDTO("A half marathon"));
    }

    @Test
    void anEmptyOrOverlongAnswerIsRefusedBeforeTheService() throws Exception {
        for (String body : List.of("{}", "{\"answer\":\"  \"}", "{\"answer\":\"" + "x".repeat(2001) + "\"}")) {
            mockMvc.perform(post("/second-brain/questions/" + QUESTION_ID + "/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        }
        verifyNoMoreInteractions(secondBrainService);
    }

    @Test
    void aQuestionThatIsNotTheCallersIsA404AndAClosedOneA409() throws Exception {
        when(secondBrainService.answer(eq(user), eq(QUESTION_ID), any(SecondBrainAnswerRequestDTO.class)))
                .thenThrow(new BusinessException(ErrorKey.SECOND_BRAIN_QUESTION_NOT_FOUND, "Question not found"));
        when(secondBrainService.skip(user, QUESTION_ID))
                .thenThrow(new BusinessException(ErrorKey.SECOND_BRAIN_QUESTION_CLOSED, "closed"));

        mockMvc.perform(post("/second-brain/questions/" + QUESTION_ID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"mine now\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_QUESTION_NOT_FOUND"));
        mockMvc.perform(post("/second-brain/questions/" + QUESTION_ID + "/skip"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorKey").value("SECOND_BRAIN_QUESTION_CLOSED"));
    }

    @Test
    void skippingAnswersTheSkippedQuestion() throws Exception {
        when(secondBrainService.skip(user, QUESTION_ID))
                .thenReturn(question(SecondBrainQuestionStatus.SKIPPED, null, null));

        mockMvc.perform(post("/second-brain/questions/" + QUESTION_ID + "/skip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.status").value("SKIPPED"))
                .andExpect(jsonPath("$.answer").isEmpty())
                .andExpect(jsonPath("$.answeredAt").isEmpty());
    }

    // ---- insight ----

    @Test
    void refreshingTheOverviewAnswersIt() throws Exception {
        when(secondBrainService.refreshInsight(user))
                .thenReturn(new SecondBrainInsightDTO("Your mornings hold.", SecondBrainVerdict.ON_COURSE, CREATED, false));

        mockMvc.perform(post("/second-brain/insight/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Your mornings hold."))
                .andExpect(jsonPath("$.verdict").value("ON_COURSE"))
                .andExpect(jsonPath("$.generatedAt").value("2026-11-11T12:00:00Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void noModelAnswersAiUnavailableTheWayOnboardingDoes() throws Exception {
        when(secondBrainService.refreshInsight(user))
                .thenThrow(new BusinessException(ErrorKey.AI_UNAVAILABLE, "Second Brain AI is unavailable"));

        mockMvc.perform(post("/second-brain/insight/refresh"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("AI_UNAVAILABLE"));
    }
}
