package beyou.beyouapp.backend.domain.aiAgent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
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
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.ScheduleResponseDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.UpdateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
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
    @Autowired
    private ChatService chatService;
    @Autowired
    private DiaryRoutineService diaryRoutineService;
    @Autowired
    private ScheduleService scheduleService;

    private UUID userId(ToolContext toolContext) {
        return (UUID) toolContext.getContext().get("userId");
    }

    private UUID chatId(ToolContext toolContext) {
        return (UUID) toolContext.getContext().get("chatId");
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

    // Context memory
    @Tool(description = "Remember stable user preferences across ALL chats (name, tone, language, "
            + "standing goals). OVERWRITES the previous global context — always send the full compact "
            + "summary, max 2000 characters. Never store secrets or sensitive data")
    Map<String, String> updateGlobalContext(String context, ToolContext toolContext) {
        log.info("AI agent is updating global context for user: {}", userId(toolContext));
        chatService.updateGlobalContext(context, userId(toolContext));
        return Map.of("success", "Global context updated");
    }

    @Tool(description = "Remember facts about THIS conversation only (task at hand, decisions made). "
            + "OVERWRITES the previous chat context — always send the full compact summary, max 1000 "
            + "characters. Never store secrets or sensitive data")
    Map<String, String> updateChatContext(String context, ToolContext toolContext) {
        log.info("AI agent is updating chat context for chat {} of user: {}",
                chatId(toolContext), userId(toolContext));
        chatService.updateChatContext(context, chatId(toolContext), userId(toolContext));
        return Map.of("success", "Chat context updated");
    }

    // Routines
    @Tool(description = "Get all user routines with their sections, habit groups and task groups "
            + "(includes the group ids needed for check/skip and the schedule if any)")
    List<DiaryRoutineResponseDTO> getUserRoutines(ToolContext toolContext) {
        log.info("AI agent is using routines tool for user: {}", userId(toolContext));
        return diaryRoutineService.getAllDiaryRoutines(userId(toolContext)).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .toList();
    }

    @Tool(description = "Get the routine scheduled for today, or null if none is scheduled")
    DiaryRoutineResponseDTO getTodayRoutine(ToolContext toolContext) {
        log.info("AI agent is using today-routine tool for user: {}", userId(toolContext));
        return diaryRoutineService.getTodayRoutineScheduled(userId(toolContext));
    }

    @Tool(description = "Create a new routine. Sections need name, iconId and HH:mm start/end times; "
            + "habitGroup/taskGroup items reference existing habitId/taskId and their times must be "
            + "inside the section time window")
    DiaryRoutineResponseDTO createUserRoutine(DiaryRoutineRequestDTO routine, ToolContext toolContext) {
        log.info("AI agent is creating a routine for user: {}", userId(toolContext));
        return diaryRoutineService.createDiaryRoutine(routine, userId(toolContext));
    }

    @Tool(description = "Edit an existing routine by its id. The structure REPLACES the current one: "
            + "send the complete routine (all sections and items), fetched first via getUserRoutines")
    DiaryRoutineResponseDTO editUserRoutine(UUID routineId, DiaryRoutineRequestDTO routine, ToolContext toolContext) {
        log.info("AI agent is editing routine {} for user: {}", routineId, userId(toolContext));
        return diaryRoutineService.updateDiaryRoutine(routineId, routine, userId(toolContext));
    }

    @Tool(description = "Delete a user routine by its id (also removes its snapshots and schedule)")
    Map<String, String> deleteUserRoutine(UUID routineId, ToolContext toolContext) {
        log.info("AI agent is deleting routine {} for user: {}", routineId, userId(toolContext));
        diaryRoutineService.deleteDiaryRoutine(routineId, userId(toolContext));
        return Map.of("success", "Routine deleted successfully");
    }

    // Schedules
    @Tool(description = "Get all routine schedules (which routine runs on which week days)")
    List<ScheduleResponseDTO> getUserSchedules(ToolContext toolContext) {
        log.info("AI agent is using schedules tool for user: {}", userId(toolContext));
        return scheduleService.findAll(userId(toolContext));
    }

    @Tool(description = "Schedule a routine on week days (MONDAY..SUNDAY). A day can only have one "
            + "routine — scheduling over an already-taken day moves that day to this routine")
    ScheduleResponseDTO createUserSchedule(CreateScheduleDTO schedule, ToolContext toolContext) {
        log.info("AI agent is creating a schedule for user: {}", userId(toolContext));
        return ScheduleResponseDTO.from(scheduleService.create(schedule, userId(toolContext)));
    }

    @Tool(description = "Update a schedule's week days by scheduleId")
    ScheduleResponseDTO updateUserSchedule(UpdateScheduleDTO schedule, ToolContext toolContext) {
        log.info("AI agent is updating schedule {} for user: {}", schedule.scheduleId(), userId(toolContext));
        return ScheduleResponseDTO.from(scheduleService.update(schedule, userId(toolContext)));
    }

    @Tool(description = "Delete a schedule by its id (the routine stays, just unscheduled)")
    Map<String, String> deleteUserSchedule(UUID scheduleId, ToolContext toolContext) {
        log.info("AI agent is deleting schedule {} for user: {}", scheduleId, userId(toolContext));
        scheduleService.delete(scheduleId, userId(toolContext));
        return Map.of("success", "Schedule deleted successfully");
    }

    // Routine check-in
    @Tool(description = "Toggle done/not-done for ONE routine item on a date. Send routineId, the date "
            + "(YYYY-MM-DD, usually today) and EITHER habitGroupDTO {habitGroupId, startTime} OR "
            + "taskGroupDTO {taskGroupId, startTime} — group ids come from the routine structure, NOT "
            + "habit/task ids. Checking awards XP: only call on explicit user request")
    RefreshUiDTO checkRoutineItem(CheckGroupRequestDTO checkRequest, ToolContext toolContext) {
        log.info("AI agent is checking a routine item on routine {} for user: {}",
                checkRequest.routineId(), userId(toolContext));
        return diaryRoutineService.checkAndUncheckGroup(checkRequest, userId(toolContext));
    }

    @Tool(description = "Skip or unskip ONE routine item on a date (skipped items don't hurt the "
            + "streak). Same shape as checkRoutineItem plus skip=true|false")
    RefreshUiDTO skipRoutineItem(SkipGroupRequestDTO skipRequest, ToolContext toolContext) {
        log.info("AI agent is skipping a routine item on routine {} for user: {}",
                skipRequest.routineId(), userId(toolContext));
        return diaryRoutineService.skipOrUnskipGroup(skipRequest, userId(toolContext));
    }
}
