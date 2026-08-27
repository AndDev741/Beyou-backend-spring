package beyou.beyouapp.backend.domain.aiAgent;

import beyou.beyouapp.backend.domain.routine.RoutineType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import jakarta.validation.Validation;

/**
 * The agent tool layer must enforce the same Bean Validation the REST
 * controllers get from @Valid — the LLM can omit fields the UI makes
 * mandatory (a habit with importance but no dificulty) — and must expose
 * feedback submission.
 */
@ExtendWith(MockitoExtension.class)
public class ToolsUnitTest {

    @Mock
    private HabitService habitService;

    @Mock
    private TaskService taskService;

    @Mock
    private FeedbackService feedbackService;

    @Mock
    private DiaryRoutineService diaryRoutineService;

    @Mock
    private GoalService goalService;

    @InjectMocks
    private Tools tools;

    private final UUID userId = UUID.randomUUID();
    private final ToolContext toolContext = new ToolContext(Map.of("userId", userId, "currentPage", "/habits"));
    private final UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(tools, "validator",
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    private CreateHabitDTO habit(Integer importance, Integer dificulty, List<UUID> categoriesId) {
        return new CreateHabitDTO("Read a book", null, null, "book",
                importance, dificulty, categoriesId, ExperienceLevel.BEGINNER);
    }

    @Test
    void createHabitWithoutDificultyIsRejectedBeforeTheService() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tools.createUserHabit(habit(3, null, List.of(categoryId)), toolContext));

        assertTrue(error.getMessage().contains("dificulty"), error.getMessage());
        verify(habitService, never()).createHabit(any(), any());
    }

    @Test
    void createHabitWithZeroImportanceIsRejectedBeforeTheService() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tools.createUserHabit(habit(0, 3, List.of(categoryId)), toolContext));

        assertTrue(error.getMessage().contains("importance"), error.getMessage());
        verify(habitService, never()).createHabit(any(), any());
    }

    @Test
    void createHabitWithoutCategoriesIsRejectedBeforeTheService() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tools.createUserHabit(habit(3, 3, List.of()), toolContext));

        assertTrue(error.getMessage().contains("categoriesId"), error.getMessage());
        verify(habitService, never()).createHabit(any(), any());
    }

    @Test
    void validHabitReachesTheService() {
        CreateHabitDTO dto = habit(3, 4, List.of(categoryId));
        when(habitService.createHabit(dto, userId))
                .thenReturn(ResponseEntity.ok(Map.of("success", "Habit saved successfully")));

        tools.createUserHabit(dto, toolContext);

        verify(habitService).createHabit(dto, userId);
    }

    @Test
    void createTaskWithoutDifficultyIsRejectedBeforeTheService() {
        CreateTaskRequestDTO dto = new CreateTaskRequestDTO(
                "Clean the desk", null, "broom", 3, null, List.of(), false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tools.createUserTask(dto, toolContext));

        assertTrue(error.getMessage().contains("difficulty"), error.getMessage());
        verify(taskService, never()).createTask(any(), any());
    }

    @Test
    void submitFeedbackDelegatesWithAgentContext() {
        Map<String, String> result = tools.submitUserFeedback(
                FeedbackCategory.BUG, "The streak counter resets at the wrong hour", toolContext);

        ArgumentCaptor<CreateFeedbackRequestDTO> captor = ArgumentCaptor.forClass(CreateFeedbackRequestDTO.class);
        verify(feedbackService).submitFeedback(captor.capture(), eq(userId));
        CreateFeedbackRequestDTO sent = captor.getValue();
        assertEquals(FeedbackCategory.BUG, sent.category());
        assertEquals("The streak counter resets at the wrong hour", sent.body());
        assertEquals("agent", sent.context().platform());
        assertEquals("/habits", sent.context().screen());
        assertEquals("Feedback submitted to the Beyou team", result.get("success"));
    }

    @Test
    void submitFeedbackWithBlankBodyIsRejectedBeforeTheService() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tools.submitUserFeedback(FeedbackCategory.OTHER, "  ", toolContext));

        assertTrue(error.getMessage().contains("body"), error.getMessage());
        verify(feedbackService, never()).submitFeedback(any(), any());
    }

    @Test
    void habitDtosAcceptTheCorrectlySpelledDifficultyAlias() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        CreateHabitDTO created = mapper.readValue("""
                {"name":"Read","iconId":"book","importance":3,"difficulty":4,
                 "categoriesId":["%s"],"experience":"BEGINNER"}""".formatted(categoryId),
                CreateHabitDTO.class);
        assertEquals(4, created.dificulty());

        EditHabitDTO edited = mapper.readValue("""
                {"habitId":"%s","name":"Read","iconId":"book","importance":2,"difficulty":5,
                 "categoriesId":["%s"]}""".formatted(UUID.randomUUID(), categoryId),
                EditHabitDTO.class);
        assertEquals(5, edited.dificulty());
    }

    /**
     * The model drops `iconId` often enough that icon-less sections reached real
     * routines, and a section with no icon reads as a hole in a list where every
     * other row has one.
     */
    @Test
    void routineAndSectionsAlwaysReachTheServiceWithAnIcon() {
        DiaryRoutineRequestDTO routine = new DiaryRoutineRequestDTO("Dia produtivo", null, RoutineType.DAILY, List.of(
                new RoutineSectionRequestDTO(null, "Manhã", null, LocalTime.of(8, 0), LocalTime.of(12, 0),
                        List.of(), List.of(), false),
                new RoutineSectionRequestDTO(null, "Tarde", "lucide:not-in-the-catalog",
                        LocalTime.of(13, 0), LocalTime.of(18, 0), List.of(), List.of(), false)), List.of());

        tools.createUserRoutine(routine, toolContext);

        ArgumentCaptor<DiaryRoutineRequestDTO> sent = ArgumentCaptor.forClass(DiaryRoutineRequestDTO.class);
        verify(diaryRoutineService).createDiaryRoutine(sent.capture(), eq(userId));
        assertEquals(AiIconCatalog.DEFAULT_ICON, sent.getValue().iconId());
        assertEquals(AiIconCatalog.DEFAULT_ICON, sent.getValue().routineSections().get(0).iconId());
        // An id the catalog does not know degrades the same way.
        assertEquals(AiIconCatalog.DEFAULT_ICON, sent.getValue().routineSections().get(1).iconId());
        // Everything else rides through untouched.
        assertEquals("Manhã", sent.getValue().routineSections().get(0).name());
        assertEquals(LocalTime.of(8, 0), sent.getValue().routineSections().get(0).startTime());
    }

    @Test
    void routineKeepsAnIconTheCatalogKnows() {
        String known = AiIconCatalog.ICONS.get(0).id();
        DiaryRoutineRequestDTO routine = new DiaryRoutineRequestDTO("Dia", known, RoutineType.DAILY, List.of(
                new RoutineSectionRequestDTO(null, "Manhã", known, LocalTime.of(8, 0), LocalTime.of(12, 0),
                        List.of(), List.of(), false)), List.of());

        tools.createUserRoutine(routine, toolContext);

        ArgumentCaptor<DiaryRoutineRequestDTO> sent = ArgumentCaptor.forClass(DiaryRoutineRequestDTO.class);
        verify(diaryRoutineService).createDiaryRoutine(sent.capture(), eq(userId));
        assertEquals(known, sent.getValue().iconId());
        assertEquals(known, sent.getValue().routineSections().get(0).iconId());
    }

    @Test
    void taskDtoAcceptsTheMisspelledDificultyAlias() throws Exception {
        CreateTaskRequestDTO dto = new ObjectMapper().readValue("""
                {"name":"Clean","iconId":"broom","importance":2,"dificulty":3,
                 "categoriesId":[],"oneTimeTask":false}""",
                CreateTaskRequestDTO.class);
        assertEquals(3, dto.difficulty());
    }

    // --- Goal identification -------------------------------------------------------
    // The agent fabricated goal ids on a real user's turn — twice, the second time after
    // getUserGoals had handed it the real ones. The prompt already forbade that, so these
    // cover the structural answer: a name it cannot invent, and a failure that teaches.

    private GoalResponseDTO goal(UUID id, String name) {
        return new GoalResponseDTO(id, name, "icon", null, 208.0, "pages", 21.0, false,
                Map.of(), null, null, null, 0, null, null, null);
    }

    @Test
    void increaseGoalResolvesByExactName() {
        UUID goalId = UUID.randomUUID();
        when(goalService.getAllGoals(userId)).thenReturn(List.of(goal(goalId, "Ler 50 Ideias")));

        tools.increaseUserGoalValue("Ler 50 Ideias", 16.0, toolContext);

        verify(goalService).increaseCurrentValue(goalId, 16.0, userId);
    }

    @Test
    void increaseGoalStillAcceptsAnId() {
        UUID goalId = UUID.randomUUID();
        when(goalService.getAllGoals(userId)).thenReturn(List.of(goal(goalId, "Ler 50 Ideias")));

        tools.increaseUserGoalValue(goalId.toString(), 1.0, toolContext);

        verify(goalService).increaseCurrentValue(goalId, 1.0, userId);
    }

    @Test
    void increaseGoalResolvesByUniqueSubstring() {
        UUID goalId = UUID.randomUUID();
        when(goalService.getAllGoals(userId))
                .thenReturn(List.of(goal(goalId, "Ler '50 Ideias de Fisica Quantica'"),
                                    goal(UUID.randomUUID(), "Resolver 7 problemas")));

        tools.increaseUserGoalValue("Fisica", 2.0, toolContext);

        verify(goalService).increaseCurrentValue(goalId, 2.0, userId);
    }

    // Guessing between two plausible goals is how you update the wrong one.
    @Test
    void increaseGoalRefusesAnAmbiguousName() {
        when(goalService.getAllGoals(userId))
                .thenReturn(List.of(goal(UUID.randomUUID(), "Read a book"),
                                    goal(UUID.randomUUID(), "Read the docs")));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> tools.increaseUserGoalValue("Read", 1.0, toolContext));

        assertEquals(ErrorKey.GOAL_NOT_FOUND, thrown.getErrorKey());
        verify(goalService, never()).increaseCurrentValue(any(), any(), any());
    }

    // The message is what the agent reads next. "Not found" left it to retry the same
    // wrong id or, as it actually did, offer to delete and recreate the goal.
    @Test
    void unresolvableGoalErrorListsTheRealGoals() {
        UUID goalId = UUID.randomUUID();
        when(goalService.getAllGoals(userId)).thenReturn(List.of(goal(goalId, "Ler 50 Ideias")));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> tools.increaseUserGoalValue("d1ce6f05-8f32-4aa5-a8eb-2f46e2d29b00", 1.0, toolContext));

        assertTrue(thrown.getMessage().contains("Ler 50 Ideias"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(goalId.toString()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Do not invent an id"), thrown.getMessage());
        verify(goalService, never()).increaseCurrentValue(any(), any(), any());
    }

    @Test
    void deleteGoalAlsoResolvesByName() {
        UUID goalId = UUID.randomUUID();
        when(goalService.getAllGoals(userId)).thenReturn(List.of(goal(goalId, "Ler 50 Ideias")));
        when(goalService.deleteGoal(goalId, userId)).thenReturn(ResponseEntity.ok(Map.of("success", "ok")));

        tools.deleteUserGoal("Ler 50 Ideias", toolContext);

        verify(goalService).deleteGoal(goalId, userId);
    }

}
