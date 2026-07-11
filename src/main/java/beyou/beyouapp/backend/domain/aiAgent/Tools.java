package beyou.beyouapp.backend.domain.aiAgent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.HabitResponseDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class Tools {

    private static final int MAX_ITEMS_PER_TYPE = 100;

    @Autowired
    private HabitService habitService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private GoalService goalService;

    private UUID userId(ToolContext toolContext) {
        return (UUID) toolContext.getContext().get("userId");
    }

    // Habits
    @Tool(description = "Get all user habits (Max items 100)")
    List<HabitResponseDTO> getUserHabits(ToolContext toolContext) {
        log.info("AI agent is using habits tool for user: {}", userId(toolContext));
        return habitService.getHabits(userId(toolContext)).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .toList();
    }

    @Tool(description = "Create a new user habit")
    ResponseEntity<Map<String, String>> createUserHabit(CreateHabitDTO habit, ToolContext toolContext) {
        log.info("AI agent is creating a habit for user: {}", userId(toolContext));
        return habitService.createHabit(habit, userId(toolContext));
    }

    @Tool(description = "Edit an existing user habit. All fields are required, send the current values for fields that should not change")
    Map<String, String> editUserHabit(EditHabitDTO habit, ToolContext toolContext) {
        log.info("AI agent is editing habit {} for user: {}", habit.habitId(), userId(toolContext));
        return habitService.editHabit(habit, userId(toolContext)).getBody();
    }

    @Tool(description = "Delete a user habit by its id. Fails if the habit is used in a routine")
    Map<String, String> deleteUserHabit(UUID habitId, ToolContext toolContext) {
        log.info("AI agent is deleting habit {} for user: {}", habitId, userId(toolContext));
        return habitService.deleteHabit(habitId, userId(toolContext)).getBody();
    }

    // Categories
    @Tool(description = "Get all user categories (Max items 100)")
    List<CategoryResponseDTO> getUserCategories(ToolContext toolContext) {
        log.info("AI agent is using categories tool for user: {}", userId(toolContext));
        return categoryService.getAllCategories(userId(toolContext)).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .toList();
    }

    @Tool(description = "Create a new user category")
    Map<String, Object> createUserCategory(CategoryRequestDTO category, ToolContext toolContext) {
        log.info("AI agent is creating a category for user: {}", userId(toolContext));
        return categoryService.createCategory(category, userId(toolContext)).getBody();
    }

    @Tool(description = "Edit an existing user category. All fields are required, send the current values for fields that should not change")
    Map<String, Object> editUserCategory(CategoryEditRequestDTO category, ToolContext toolContext) {
        log.info("AI agent is editing category {} for user: {}", category.categoryId(), userId(toolContext));
        return categoryService.editCategory(category, userId(toolContext)).getBody();
    }

    @Tool(description = "Delete a user category by its id. Fails if the category is used in a habit")
    Map<String, String> deleteUserCategory(String categoryId, ToolContext toolContext) {
        log.info("AI agent is deleting category {} for user: {}", categoryId, userId(toolContext));
        return categoryService.deleteCategory(categoryId, userId(toolContext)).getBody();
    }

    // Tasks
    @Tool(description = "Get all user tasks (Max items 100)")
    List<TaskResponseDTO> getUserTasks(ToolContext toolContext) {
        log.info("AI agent is using tasks tool for user: {}", userId(toolContext));
        return taskService.getAllTasks(userId(toolContext)).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .toList();
    }

    @Tool(description = "Create a new user task")
    Map<String, String> createUserTask(CreateTaskRequestDTO task, ToolContext toolContext) {
        log.info("AI agent is creating a task for user: {}", userId(toolContext));
        return taskService.createTask(task, userId(toolContext)).getBody();
    }

    @Tool(description = "Edit an existing user task. All fields are required, send the current values for fields that should not change")
    Map<String, String> editUserTask(EditTaskRequestDTO task, ToolContext toolContext) {
        log.info("AI agent is editing task {} for user: {}", task.taskId(), userId(toolContext));
        return taskService.editTask(task, userId(toolContext)).getBody();
    }

    @Tool(description = "Delete a user task by its id. Fails if the task is used in a routine")
    Map<String, String> deleteUserTask(UUID taskId, ToolContext toolContext) {
        log.info("AI agent is deleting task {} for user: {}", taskId, userId(toolContext));
        return taskService.deleteTask(taskId, userId(toolContext)).getBody();
    }

    // Goals
    @Tool(description = "Get all user goals (Max items 100)")
    List<GoalResponseDTO> getUserGoals(ToolContext toolContext) {
        log.info("AI agent is using goals tool for user: {}", userId(toolContext));
        return goalService.getAllGoals(userId(toolContext)).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .toList();
    }

    @Tool(description = "Create a new user goal")
    Map<String, String> createUserGoal(CreateGoalRequestDTO goal, ToolContext toolContext) {
        log.info("AI agent is creating a goal for user: {}", userId(toolContext));
        return goalService.createGoal(goal, userId(toolContext)).getBody();
    }

    @Tool(description = "Edit an existing user goal. All fields are required, send the current values for fields that should not change")
    Map<String, String> editUserGoal(EditGoalRequestDTO goal, ToolContext toolContext) {
        log.info("AI agent is editing goal {} for user: {}", goal.goalId(), userId(toolContext));
        return goalService.editGoal(goal, userId(toolContext)).getBody();
    }

    @Tool(description = "Delete a user goal by its id")
    Map<String, String> deleteUserGoal(UUID goalId, ToolContext toolContext) {
        log.info("AI agent is deleting goal {} for user: {}", goalId, userId(toolContext));
        return goalService.deleteGoal(goalId, userId(toolContext)).getBody();
    }

    @Tool(description = "Toggle a goal completion by its id. Completing awards XP, un-completing removes it")
    RefreshUiDTO completeUserGoal(UUID goalId, ToolContext toolContext) {
        log.info("AI agent is toggling completion of goal {} for user: {}", goalId, userId(toolContext));
        return goalService.checkGoal(goalId, userId(toolContext));
    }

    @Tool(description = "Increase a goal's current value by 1")
    GoalResponseDTO increaseUserGoalValue(UUID goalId, ToolContext toolContext) {
        log.info("AI agent is increasing goal {} for user: {}", goalId, userId(toolContext));
        return goalService.increaseCurrentValue(goalId, userId(toolContext));
    }

    @Tool(description = "Decrease a goal's current value by 1 (never below 0)")
    GoalResponseDTO decreaseUserGoalValue(UUID goalId, ToolContext toolContext) {
        log.info("AI agent is decreasing goal {} for user: {}", goalId, userId(toolContext));
        return goalService.decreaseCurrentValue(goalId, userId(toolContext));
    }
}
