package beyou.beyouapp.backend.unit.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalMapper;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.domain.goal.util.GoalXpCalculator;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.goal.GoalNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class goalServiceUnitTest {
    @Mock
    private GoalRepository goalRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    XpCalculatorService xpCalculatorService;

    @Mock
    RefreshUiDtoBuilder refreshUiDtoBuilder;

    private GoalMapper goalMapper = new GoalMapper();

    GoalService goalService;

    UUID goalId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Goal goal = new Goal();
    User user = new User();

    @BeforeEach
    void setup() {
        goalRepository.deleteAll();

        goal.setId(goalId);
        goal.setName("Test 1");
        goal.setTargetValue(10.0);
        goal.setCurrentValue(0.0);
        goal.setStartDate(LocalDate.now().minusDays(2));
        goal.setEndDate(LocalDate.now());
        goal.setCategories(new java.util.ArrayList<>());
        user.setId(userId);
        goal.setUser(user);

        goalService = new GoalService(goalRepository, categoryService, goalMapper, xpCalculatorService,
                refreshUiDtoBuilder);

    }

    @Test
    void shouldGetGoalSuccessfully() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));

        Goal assertGoal = goalService.getGoal(goalId);

        verify(goalRepository, times(1)).findById(goalId);
        assertGoal.equals(goal);
        assertEquals(assertGoal.getName(), goal.getName());
    }

    @Test
    void shouldGetAllGoalsOfTheUser() {
        when(goalRepository.findAllByUserId(userId)).thenReturn(Optional.of(List.of(goal)));
        List<GoalResponseDTO> assertGoals = goalService.getAllGoals(userId);

        verify(goalRepository, times(1)).findAllByUserId(userId);
        assertEquals(assertGoals.size(), 1);
        assertEquals(assertGoals.get(0).name(), goal.getName());
    }

    @Test
    void shoudTrowExceptionIfGoalNotFound() {
        Exception exception = assertThrows(GoalNotFound.class, () -> {
            goalService.getGoal(UUID.randomUUID());
        });

        assertEquals("Goal not found", exception.getMessage());
    }

    @Test
    void shouldThrowException_whenNoGoalsForUser() {
        when(goalRepository.findAllByUserId(userId)).thenReturn(Optional.empty());

        Exception exception = assertThrows(UserNotFound.class,
                () -> goalService.getAllGoals(userId));

        assertEquals("User not found when trying to get goals", exception.getMessage());
    }

    @Test
    void shouldCreateGoalSuccessfully() {
        CreateGoalRequestDTO dto = new CreateGoalRequestDTO(
                "Name", "icon", "desc", 100.0, "unit", 0.0,
                List.of(UUID.randomUUID()), "motivation",
                LocalDate.now(), LocalDate.now().plusDays(1),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);
        Category cat = new Category();
        cat.setId(dto.categoriesId().get(0));
        when(categoryService.getCategory(dto.categoriesId().get(0))).thenReturn(cat);
        when(goalRepository.save(any(Goal.class))).thenReturn(goal);

        ResponseEntity<Map<String, String>> response = goalService.createGoal(dto, user);

        assertEquals(200, response.getStatusCode().value());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals("Goal created successfully", body.get("success"));
    }

    @Test
    void shouldReturnBadRequest_whenCreateGoalSaveFails() {
        CreateGoalRequestDTO dto = new CreateGoalRequestDTO(
                "Name", "icon", "desc", 100.0, "unit", 0.0,
                List.of(UUID.randomUUID()), "motivation",
                LocalDate.now(), LocalDate.now().plusDays(1),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);
        when(categoryService.getCategory(any(UUID.class))).thenReturn(new Category());
        doThrow(new RuntimeException()).when(goalRepository).save(any(Goal.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> goalService.createGoal(dto, user));
        assertEquals(ErrorKey.GOAL_CREATE_FAILED, exception.getErrorKey());
        assertEquals("Error trying to create goal", exception.getMessage());
    }

    @Test
    void shouldEditGoalSuccessfully() {
        EditGoalRequestDTO dto = new EditGoalRequestDTO(
                goalId, "NewName", "icon", "desc", 200.0, "unit", 10.0,
                false, List.of(UUID.randomUUID()), "motivation",
                LocalDate.now(), LocalDate.now().plusDays(2),
                GoalStatus.IN_PROGRESS, GoalTerm.MEDIUM_TERM);
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(categoryService.getCategory(any(UUID.class))).thenReturn(new Category());
        when(goalRepository.save(goal)).thenReturn(goal);

        ResponseEntity<Map<String, String>> response = goalService.editGoal(dto, userId);

        assertEquals(200, response.getStatusCode().value());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals("Goal edited successfully", body.get("success"));
    }

    @Test
    void shouldThrowGoalNotFound_whenEditGoalUnknown() {
        EditGoalRequestDTO dto = new EditGoalRequestDTO(
                UUID.randomUUID(), "name", "icon", "desc", 0.0, "u", 0.0,
                false, List.of(), "", LocalDate.now(), LocalDate.now(),
                GoalStatus.NOT_STARTED, GoalTerm.LONG_TERM);
        when(goalRepository.findById(dto.goalId())).thenReturn(Optional.empty());

        assertThrows(GoalNotFound.class, () -> goalService.editGoal(dto, userId));
    }

    @Test
    void shouldThrowGoalNotFound_whenEditGoalUserMismatch() {
        EditGoalRequestDTO dto = new EditGoalRequestDTO(
                goalId, "n", "i", "d", 0.0, "u", 0.0,
                false, List.of(), "", LocalDate.now(), LocalDate.now(),
                GoalStatus.NOT_STARTED, GoalTerm.LONG_TERM);
        User other = new User();
        other.setId(UUID.randomUUID());
        goal.setUser(other);
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> goalService.editGoal(dto, userId));
        assertEquals(ErrorKey.GOAL_NOT_OWNED, exception.getErrorKey());
    }

    @Test
    void shouldReturnBadRequest_whenEditGoalSaveFails() {
        EditGoalRequestDTO dto = new EditGoalRequestDTO(
                goalId, "n", "i", "d", 0.0, "u", 0.0,
                false, List.of(UUID.randomUUID()), "", LocalDate.now(), LocalDate.now(),
                GoalStatus.NOT_STARTED, GoalTerm.LONG_TERM);
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(categoryService.getCategory(any(UUID.class))).thenReturn(new Category());
        doThrow(new RuntimeException()).when(goalRepository).save(goal);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> goalService.editGoal(dto, userId));
        assertEquals(ErrorKey.GOAL_EDIT_FAILED, exception.getErrorKey());
        assertEquals("Error trying to edit goal", exception.getMessage());
    }

    @Test
    void shouldDeleteGoalSuccessfully() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));

        ResponseEntity<Map<String, String>> response = goalService.deleteGoal(goalId, userId);

        verify(goalRepository, times(1)).delete(goal);
        assertEquals(200, response.getStatusCode().value());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals("Goal deleted successfully", body.get("success"));
    }

    @Test
    void shouldThrowGoalNotFound_whenDeleteGoalUnknown() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.empty());

        assertThrows(GoalNotFound.class, () -> goalService.deleteGoal(goalId, userId));
    }

    @Test
    void shouldThrowGoalNotFound_whenDeleteGoalUserMismatch() {
        User other = new User();
        other.setId(UUID.randomUUID());
        goal.setUser(other);
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> goalService.deleteGoal(goalId, userId));
        assertEquals(ErrorKey.GOAL_NOT_OWNED, exception.getErrorKey());
    }

    @Test
    void shouldReturnBadRequest_whenDeleteGoalFails() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        doThrow(new RuntimeException()).when(goalRepository).delete(goal);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> goalService.deleteGoal(goalId, userId));
        assertEquals(ErrorKey.GOAL_DELETE_FAILED, exception.getErrorKey());
        assertEquals("Error trying to delete goal", exception.getMessage());
    }

    @Test
    void shouldEditEntityCorrectly() {
        when(goalRepository.save(goal)).thenReturn(goal);

        Goal result = goalService.editEntity(goal);

        assertEquals(goal, result);
        verify(goalRepository, times(1)).save(goal);
    }

    @Test
    void shouldMarkAsCompleteSuccessfully() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        double xp = GoalXpCalculator.calculateXp(goal);

        when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any()))
        .thenAnswer(invocation -> new RefreshUiDTO(null, invocation.getArgument(1), null, null));

        RefreshUiDTO response = goalService.checkGoal(goalId, userId);

        assertNotNull(response);
        assertEquals(GoalStatus.COMPLETED, goal.getStatus());
        assertEquals(LocalDate.now(), goal.getCompleteDate());
        verify(xpCalculatorService, times(1)).addXpToUserGoalAndCategoriesAndPersist(xp, goal, goal.getCategories());
    }

    @Test
    void shouldRemoveCompleteSuccessfully() {
        goal.setComplete(true);
        double xp = GoalXpCalculator.calculateXp(goal);

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any()))
        .thenAnswer(invocation -> new RefreshUiDTO(null, invocation.getArgument(1), null, null));

        RefreshUiDTO response = goalService.checkGoal(goalId, userId);

        assertNotNull(response);
        assertEquals(false, goal.getComplete());
        assertEquals(GoalStatus.IN_PROGRESS, goal.getStatus());
        assertEquals(null, goal.getCompleteDate());
        verify(xpCalculatorService, times(1)).removeXpOfUserGoalAndCategoriesAndPersist(xp, goal, goal.getCategories());
    }

    @Test
    void shouldIncrementTheCurrentValueSuccessfully() {

        goal.setCurrentValue(15.0);

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalResponseDTO response = goalService.increaseCurrentValue(goalId, userId);

        assertEquals(16.0, response.currentValue());

    }

    @Test
    void shouldDecrementTheCurrentValueSuccessfully() {

        goal.setCurrentValue(15.0);

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalResponseDTO response = goalService.decreaseCurrentValue(goalId, userId);

        assertEquals(14.0, response.currentValue());

    }

}
