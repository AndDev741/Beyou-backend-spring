package beyou.beyouapp.backend.domain.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.exceptions.goal.GoalNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class goalServiceTest {
    @Mock
    GoalRepository goalRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    CategoryService categoryService;

    @InjectMocks
    GoalService goalService;

    UUID goalId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Goal goal = new Goal();
    User user = new User();
    @BeforeEach
    void setup() {
        goalRepository.deleteAll();
        userRepository.deleteAll();

        goal.setId(goalId);
        goal.setName("Test 1");
        goal.setTargetValue(10.0);
        goal.setStartDate(LocalDate.now().minusDays(2));
        goal.setEndDate(LocalDate.now());
        user.setId(userId);
        goal.setUser(user);
        
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

        List<Goal> assertGoals = goalService.getAllGoals(userId);

        verify(goalRepository, times(1)).findAllByUserId(userId);
        assertEquals(assertGoals.size(), 1);
        assertEquals(assertGoals.get(0).getName(), goal.getName());
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
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        Category cat = new Category(); cat.setId(dto.categoriesId().get(0));
        when(categoryService.getCategory(dto.categoriesId().get(0))).thenReturn(cat);
        when(goalRepository.save(any(Goal.class))).thenReturn(goal);

        ResponseEntity<Map<String, String>> response = goalService.createGoal(dto, userId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Goal created successfully", response.getBody().get("success"));
    }

    @Test
    void shouldThrowUserNotFound_whenCreateGoalWithUnknownUser() {
        CreateGoalRequestDTO dto = new CreateGoalRequestDTO(
                "Name", "icon", "desc", 100.0, "unit", 0.0,
                List.of(UUID.randomUUID()), "motivation",
                LocalDate.now(), LocalDate.now().plusDays(1),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(UserNotFound.class, () -> goalService.createGoal(dto, userId));
    }

    @Test
    void shouldReturnBadRequest_whenCreateGoalSaveFails() {
        CreateGoalRequestDTO dto = new CreateGoalRequestDTO(
                "Name", "icon", "desc", 100.0, "unit", 0.0,
                List.of(UUID.randomUUID()), "motivation",
                LocalDate.now(), LocalDate.now().plusDays(1),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(categoryService.getCategory(any(UUID.class))).thenReturn(new Category());
        doThrow(new RuntimeException()).when(goalRepository).save(any(Goal.class));

        ResponseEntity<Map<String, String>> response = goalService.createGoal(dto, userId);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Error trying to create goal", response.getBody().get("error"));
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
        assertEquals("Goal edited successfully", response.getBody().get("success"));
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
        User other = new User(); other.setId(UUID.randomUUID());
        goal.setUser(other);
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));

        assertThrows(GoalNotFound.class, () -> goalService.editGoal(dto, userId));
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

        ResponseEntity<Map<String, String>> response = goalService.editGoal(dto, userId);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Error trying to edit goal", response.getBody().get("error"));
    }

    @Test
    void shouldDeleteGoalSuccessfully() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));

        ResponseEntity<Map<String, String>> response = goalService.deleteGoal(goalId, userId);

        verify(goalRepository, times(1)).delete(goal);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("Goal deleted successfully", response.getBody().get("success"));
    }

    @Test
    void shouldThrowGoalNotFound_whenDeleteGoalUnknown() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.empty());

        assertThrows(GoalNotFound.class, () -> goalService.deleteGoal(goalId, userId));
    }

    @Test
    void shouldThrowGoalNotFound_whenDeleteGoalUserMismatch() {
        User other = new User(); other.setId(UUID.randomUUID());
        goal.setUser(other);
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));

        assertThrows(GoalNotFound.class, () -> goalService.deleteGoal(goalId, userId));
    }

    @Test
    void shouldReturnBadRequest_whenDeleteGoalFails() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        doThrow(new RuntimeException()).when(goalRepository).delete(goal);

        ResponseEntity<Map<String, String>> response = goalService.deleteGoal(goalId, userId);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Error trying to delete goal", response.getBody().get("error"));
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
        when(goalRepository.save(goal)).thenReturn(goal);

        Goal response = goalService.checkGoal(goalId, userId);

        assertEquals(true, response.getComplete());
        assertEquals( GoalStatus.COMPLETED, response.getStatus());
        assertEquals(LocalDate.now(), response.getCompleteDate());
    }

    @Test
    void shouldRemoveCompleteSuccessfully() {
        goal.setComplete(true);

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        Goal response = goalService.checkGoal(goalId, userId);

        assertEquals(false, response.getComplete());
        assertEquals( GoalStatus.IN_PROGRESS, response.getStatus());
        assertEquals(null, response.getCompleteDate());
    }

    @Test
    void shouldIncrementTheCurrentValueSuccessfully() {

        goal.setCurrentValue(15.0);

        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        Goal response = goalService.increaseCurrentValue(goalId, userId);

        assertEquals(16.0, response.getCurrentValue());


    }

}
