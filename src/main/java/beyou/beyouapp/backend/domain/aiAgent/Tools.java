package beyou.beyouapp.backend.domain.aiAgent;

import beyou.beyouapp.backend.domain.routine.RoutineType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackContextDTO;
import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.CreateMicroTaskRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusDayResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.ReorderMicroTasksRequestDTO;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
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
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineItemRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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
    @Autowired
    private UserService userService;
    @Autowired
    private FeedbackService feedbackService;
    @Autowired
    private FocusService focusService;
    @Autowired
    private Validator validator;

    private UUID userId(ToolContext toolContext) {
        return (UUID) toolContext.getContext().get("userId");
    }

    private UUID chatId(ToolContext toolContext) {
        return (UUID) toolContext.getContext().get("chatId");
    }

    private String currentPage(ToolContext toolContext) {
        return (String) toolContext.getContext().get("currentPage");
    }

    /**
     * The routine entry the person has open in Focus Mode, or null when they are not in it.
     *
     * <p>Never used to fill in an omitted argument. It is here so that a tool refusing a missing
     * itemGroupId can name the one the person is actually looking at, which is the difference
     * between the model asking a useful question and the model guessing.
     */
    private UUID selectedFocusItem(ToolContext toolContext) {
        return (UUID) toolContext.getContext().get("selectedItemGroupId");
    }

    /**
     * The whole User, for the services that take one.
     *
     * <p>Everything else in here passes a userId, but FocusService needs the entity: the day a
     * micro-task or a cycle is filed under comes from the owner's own timezone via
     * {@link UserDateResolver}, not from the server's clock.
     */
    private User loadUser(ToolContext toolContext) {
        return userService.findUserById(userId(toolContext));
    }

    /**
     * The REST controllers enforce Bean Validation via @Valid, but tool calls
     * reach the services directly — the LLM can omit or mangle fields the UI
     * makes mandatory (e.g. a habit with importance but no dificulty). Same
     * constraints, enforced here; the message lists every violated field so
     * the model can fix its next attempt.
     */
    private <T> T valid(T dto) {
        // A model that sends the fields without the declared wrapper object binds the
        // whole DTO as null; the validator's own HV000116 for that says nothing usable.
        if (dto == null) {
            throw new IllegalArgumentException(
                    "Missing tool arguments: send a JSON object with the fields this tool's schema declares");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("Invalid " + dto.getClass().getSimpleName() + ": " + details);
        }
        return dto;
    }

    /** Lenient on the leading zero, one optional seconds block. */
    private static final DateTimeFormatter TOOL_TIME = DateTimeFormatter.ofPattern("H:mm[:ss]");

    /**
     * Times reach this class as raw model output, and a bare LocalTime.parse escapes
     * the valid() contract: "7:00" (no leading zero) threw DateTimeParseException and
     * a missing value threw NPE, neither telling the model what to send. The near-miss
     * is accepted; garbage comes back as a message the model can act on.
     */
    private LocalTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field + ": send a time as HH:mm, e.g. 07:30");
        }
        try {
            return LocalTime.parse(value.trim(), TOOL_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + field + " \"" + value
                    + "\": send a time as HH:mm between 00:00 and 23:59, e.g. 07:30");
        }
    }

    /**
     * Same contract as {@link #parseTime}, for a day. Null and blank come back as null rather than
     * as a refusal: the tools that take a date all mean "today" when it is left out, and today is
     * the user's own day, which only the caller can resolve.
     */
    private LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + field + " \"" + value
                    + "\": send a date as YYYY-MM-DD, e.g. 2026-08-30");
        }
    }

    /**
     * The routine entry a micro-task hangs off, refused rather than guessed when it is missing.
     *
     * <p>Writing to the wrong entry is silent: the row lands on a list the person is not looking
     * at, and they find it later without a way to explain it. So a missing id is an error, and the
     * message says where the real one lives — naming the entry open in Focus Mode when there is
     * one, which is the id the model most likely meant.
     */
    private UUID requireItemGroup(UUID itemGroupId, ToolContext toolContext) {
        if (itemGroupId != null) {
            return itemGroupId;
        }
        UUID selected = selectedFocusItem(toolContext);
        throw new IllegalArgumentException("Missing itemGroupId: it is a routine ENTRY id "
                + "(habitGroupId or taskGroupId from getUserRoutines), never a habit or task id"
                + (selected == null
                        ? ""
                        : ". The user has entry " + selected + " open in Focus Mode right now"));
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
        return habitService.createHabit(valid(habit), userId(toolContext));
    }

    @Tool(description = "Edit an existing user habit. All fields are required, send the current values for fields that should not change")
    Map<String, String> editUserHabit(EditHabitDTO habit, ToolContext toolContext) {
        log.info("AI agent is editing habit {} for user: {}", habit.habitId(), userId(toolContext));
        return habitService.editHabit(valid(habit), userId(toolContext)).getBody();
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
        return categoryService.createCategory(valid(category), userId(toolContext)).getBody();
    }

    @Tool(description = "Edit an existing user category. All fields are required, send the current values for fields that should not change")
    Map<String, Object> editUserCategory(CategoryEditRequestDTO category, ToolContext toolContext) {
        log.info("AI agent is editing category {} for user: {}", category.categoryId(), userId(toolContext));
        return categoryService.editCategory(valid(category), userId(toolContext)).getBody();
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
        return taskService.createTask(valid(task), userId(toolContext)).getBody();
    }

    @Tool(description = "Edit an existing user task. All fields are required, send the current values for fields that should not change")
    Map<String, String> editUserTask(EditTaskRequestDTO task, ToolContext toolContext) {
        log.info("AI agent is editing task {} for user: {}", task.taskId(), userId(toolContext));
        return taskService.editTask(valid(task), userId(toolContext)).getBody();
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

    @Tool(description = "Create a new user goal. A goal cannot be created already completed: a COMPLETED status starts as IN_PROGRESS")
    Map<String, String> createUserGoal(CreateGoalRequestDTO goal, ToolContext toolContext) {
        log.info("AI agent is creating a goal for user: {}", userId(toolContext));
        return goalService.createGoal(valid(goal), userId(toolContext)).getBody();
    }

    @Tool(description = "Edit an existing user goal. All fields are required, send the current values for fields that should not change. Completion is not editable here: 'complete' is ignored and a COMPLETED status is refused — use the goal completion tool, which is what moves the XP")
    Map<String, String> editUserGoal(EditGoalRequestDTO goal, ToolContext toolContext) {
        log.info("AI agent is editing goal {} for user: {}", goal.goalId(), userId(toolContext));
        return goalService.editGoal(valid(goal), userId(toolContext)).getBody();
    }

    @Tool(description = "Delete a user goal, by id or by name")
    Map<String, String> deleteUserGoal(@ToolParam(description = "The goal: its id from getUserGoals, or the goal name as the user said it") String goal, ToolContext toolContext) {
        UUID userId = userId(toolContext);
        UUID goalId = resolveGoalId(goal, userId);
        log.info("AI agent is deleting goal {} for user: {}", goalId, userId);
        return goalService.deleteGoal(goalId, userId).getBody();
    }

    @Tool(description = "Toggle a goal completion, by id or by name. Completing awards XP, un-completing removes it")
    RefreshUiDTO completeUserGoal(@ToolParam(description = "The goal: its id from getUserGoals, or the goal name as the user said it") String goal, ToolContext toolContext) {
        UUID userId = userId(toolContext);
        UUID goalId = resolveGoalId(goal, userId);
        log.info("AI agent is toggling completion of goal {} for user: {}", goalId, userId);
        return goalService.checkGoal(goalId, userId);
    }

    @Tool(description = "Increase a goal's current value by a given amount (defaults to 1 if not specified). Identify the goal by id or by name")
    GoalResponseDTO increaseUserGoalValue(
            @ToolParam(description = "The goal: its id from getUserGoals, or the goal name as the user said it") String goal,
            @ToolParam(description = "Amount to increase by, optional (defaults to 1)") Double value,
            ToolContext toolContext) {
        UUID userId = userId(toolContext);
        UUID goalId = resolveGoalId(goal, userId);
        log.info("AI agent is increasing goal {} for user: {}", goalId, userId);
        return goalService.increaseCurrentValue(goalId, value, userId);
    }

    @Tool(description = "Decrease a goal's current value by a given amount (defaults to 1 if not specified, never below 0). Identify the goal by id or by name")
    GoalResponseDTO decreaseUserGoalValue(
            @ToolParam(description = "The goal: its id from getUserGoals, or the goal name as the user said it") String goal,
            @ToolParam(description = "Amount to decrease by, optional (defaults to 1)") Double value,
            ToolContext toolContext) {
        UUID userId = userId(toolContext);
        UUID goalId = resolveGoalId(goal, userId);
        log.info("AI agent is decreasing goal {} for user: {}", goalId, userId);
        return goalService.decreaseCurrentValue(goalId, value, userId);
    }


    /**
     * Turn whatever the model sent for a goal into an id that exists.
     *
     * <p>The agent used to take a raw {@code UUID} here, and it fabricated them: on the
     * turn that produced this change it sent two ids in a row that were in nobody's
     * database, the second one *after* having called {@code getUserGoals} and been handed
     * the real ones. The prompt already forbids inventing ids, so the fix is not another
     * rule — it is removing the field that can be invented. A name is something the user
     * actually said, and resolving it is the server's job.
     *
     * <p>An id is still accepted, because that is what {@code getUserGoals} returns and
     * there is no reason to make the correct path harder. Names match exactly first, then
     * as a unique substring: two goals containing "read" is ambiguous, and guessing between
     * them is how you update the wrong one.
     */
    private UUID resolveGoalId(String sent, UUID userId) {
        List<GoalResponseDTO> goals = goalService.getAllGoals(userId);
        String trimmed = sent == null ? "" : sent.trim();
        if (!trimmed.isBlank()) {
            for (GoalResponseDTO goal : goals) {
                if (goal.id().toString().equalsIgnoreCase(trimmed)) {
                    return goal.id();
                }
            }
            UUID exact = uniqueMatch(goals, name -> name.equalsIgnoreCase(trimmed));
            if (exact != null) {
                return exact;
            }
            String lowered = trimmed.toLowerCase();
            UUID partial = uniqueMatch(goals, name -> name.toLowerCase().contains(lowered));
            if (partial != null) {
                return partial;
            }
        }
        throw new BusinessException(ErrorKey.GOAL_NOT_FOUND, goalNotResolvedMessage(trimmed, goals));
    }

    private static UUID uniqueMatch(List<GoalResponseDTO> goals, java.util.function.Predicate<String> test) {
        List<GoalResponseDTO> hits = goals.stream()
                .filter(goal -> goal.name() != null && test.test(goal.name()))
                .toList();
        return hits.size() == 1 ? hits.get(0).id() : null;
    }

    /**
     * The error the agent reads when resolution fails. It lists the user's real goals so
     * the next attempt can be right, following {@code ItemGroupService}: a message that
     * only says "not found" leaves the model to either give up or retry the same wrong
     * value, and on the reported turn it did something worse — it offered to delete the
     * goal and recreate it, which is how the user ended up with a duplicate.
     *
     * <p>Users never see this text; the frontend renders GOAL_NOT_FOUND from its own
     * translations.
     */
    private static String goalNotResolvedMessage(String sent, List<GoalResponseDTO> goals) {
        if (goals.isEmpty()) {
            return "This user has no goals at all, so '" + sent + "' cannot be resolved. "
                    + "Ask before creating one.";
        }
        String available = goals.stream()
                .map(goal -> goal.name() + " -> " + goal.id())
                .collect(Collectors.joining("; "));
        return "No goal matches '" + sent + "'. Do not invent an id or create a replacement. "
                + "The user's goals are: " + available;
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

    @Tool(description = "Create a new DAILY routine: sections with HH:mm windows. For a plain "
            + "checklist with no times use createUserListRoutine instead. Sections need name, "
            + "iconId and HH:mm start/end times; "
            + "habitGroup/taskGroup items reference existing habitId/taskId and their times must be "
            + "inside the section time window. Send NO id anywhere in this payload — not for the "
            + "routine, the sections or the items; the server assigns them and ignores any id you "
            + "send here. If the routine needs a habit or task the user does "
            + "not have yet, create it first with createUserHabit/createUserTask — never point an "
            + "item at an unrelated habit just because it exists")
    DiaryRoutineResponseDTO createUserRoutine(DiaryRoutineRequestDTO routine, ToolContext toolContext) {
        log.info("AI agent is creating a routine for user: {}", userId(toolContext));
        return diaryRoutineService.createDiaryRoutine(valid(withIcons(routine)), userId(toolContext));
    }

    @Tool(description = "FULL RESTRUCTURE of a routine: the structure REPLACES the current one — any "
            + "section or item you omit is DELETED. Send the complete routine fetched first via "
            + "getUserRoutines. For adding or removing a single item prefer addTaskToRoutineSection / "
            + "addHabitToRoutineSection / removeRoutineItem")
    DiaryRoutineResponseDTO editUserRoutine(UUID routineId, DiaryRoutineRequestDTO routine, ToolContext toolContext) {
        log.info("AI agent is editing routine {} for user: {}", routineId, userId(toolContext));
        return diaryRoutineService.updateDiaryRoutine(routineId, valid(withIcons(routine)), userId(toolContext));
    }

    @Tool(description = "Create a LIST routine: a flat checklist with NO sections and NO times, "
            + "which the user ticks off whenever they like during the day. Send only name, iconId "
            + "and items; each item names EXACTLY ONE of habitId or taskId, never both and never "
            + "neither, and the order you send them is the order the user sees. Send NO id on any "
            + "item — the server assigns them. Do NOT send routineSections; a list has none. If the "
            + "user does not have the habit or task yet, create it first with "
            + "createUserHabit/createUserTask. A list routine still has to be scheduled with "
            + "createSchedule before it shows on the dashboard, exactly like a daily one")
    DiaryRoutineResponseDTO createUserListRoutine(String name, String iconId,
            List<RoutineItemRequestDTO> items, ToolContext toolContext) {
        log.info("AI agent is creating a list routine for user: {}", userId(toolContext));
        DiaryRoutineRequestDTO routine = new DiaryRoutineRequestDTO(
                name, AiIconCatalog.orDefault(iconId), RoutineType.LIST, null, items);
        return diaryRoutineService.createDiaryRoutine(valid(routine), userId(toolContext));
    }

    @Tool(description = "FULL REPLACE of a LIST routine's items: the list you send REPLACES the "
            + "current one, and any item you omit is DELETED along with its check history. Fetch the "
            + "routine first with getUserRoutines and echo back the id of every item you are keeping, "
            + "otherwise the user loses the record of every day they ticked it. Each item names "
            + "EXACTLY ONE of habitId or taskId. Order is the order you send")
    DiaryRoutineResponseDTO editUserListRoutine(UUID routineId, String name, String iconId,
            List<RoutineItemRequestDTO> items, ToolContext toolContext) {
        log.info("AI agent is editing list routine {} for user: {}", routineId, userId(toolContext));
        DiaryRoutineRequestDTO routine = new DiaryRoutineRequestDTO(
                name, AiIconCatalog.orDefault(iconId), RoutineType.LIST, null, items);
        return diaryRoutineService.updateDiaryRoutine(routineId, valid(routine), userId(toolContext));
    }

    /**
     * Every routine and section leaves here with an icon.
     *
     * The model is asked for one and usually gives one, but it drops the field often
     * enough that icon-less sections reached real routines, and a section with no
     * icon reads as a hole in a list where everything else has one. Every other
     * creation path already defaults it (the onboarding wizard runs the same
     * catalog), so this is the agent catching up rather than a new rule. An id the
     * catalog does not know degrades to the default too.
     */
    private DiaryRoutineRequestDTO withIcons(DiaryRoutineRequestDTO routine) {
        if (routine == null) {
            return null;
        }
        List<RoutineSectionRequestDTO> sections = routine.routineSections() == null ? null
                : routine.routineSections().stream()
                        .map(section -> new RoutineSectionRequestDTO(
                                section.id(), section.name(), AiIconCatalog.orDefault(section.iconId()),
                                section.startTime(), section.endTime(),
                                section.taskGroup(), section.habitGroup(), section.favorite()))
                        .toList();
        // routine.type(), NOT a hardcoded DAILY: this helper is on the path of every routine
        // the agent creates or edits, and forcing the type here would silently turn a list
        // back into a daily routine with no sections, which validation then rejects with a
        // message about sections the model never sent.
        return new DiaryRoutineRequestDTO(routine.name(), AiIconCatalog.orDefault(routine.iconId()),
                routine.type(), sections, routine.items());
    }

    @Tool(description = "Add ONE existing task to a routine section. Times are HH:mm inside the "
            + "section window. routineId/sectionId come from getUserRoutines, taskId from getUserTasks")
    DiaryRoutineResponseDTO addTaskToRoutineSection(UUID routineId, UUID sectionId, UUID taskId,
            String startTime, String endTime, ToolContext toolContext) {
        log.info("AI agent is adding task {} to routine {} for user: {}", taskId, routineId, userId(toolContext));
        return diaryRoutineService.addTaskToSection(routineId, sectionId, taskId,
                parseTime(startTime, "startTime"), parseTime(endTime, "endTime"), userId(toolContext));
    }

    @Tool(description = "Add ONE existing habit to a routine section. Times are HH:mm inside the "
            + "section window. routineId/sectionId come from getUserRoutines, habitId from getUserHabits. "
            + "If the user has no habit for what they asked for, create it with createUserHabit first "
            + "rather than adding a habit that only looks close")
    DiaryRoutineResponseDTO addHabitToRoutineSection(UUID routineId, UUID sectionId, UUID habitId,
            String startTime, String endTime, ToolContext toolContext) {
        log.info("AI agent is adding habit {} to routine {} for user: {}", habitId, routineId, userId(toolContext));
        return diaryRoutineService.addHabitToSection(routineId, sectionId, habitId,
                parseTime(startTime, "startTime"), parseTime(endTime, "endTime"), userId(toolContext));
    }

    @Tool(description = "Remove ONE item from a routine by its GROUP id (habitGroup/taskGroup id from "
            + "the routine structure, NOT the habit/task id). The habit/task itself is kept. Confirm "
            + "with the user before removing")
    DiaryRoutineResponseDTO removeRoutineItem(UUID routineId, UUID groupId, ToolContext toolContext) {
        log.info("AI agent is removing group {} from routine {} for user: {}", groupId, routineId, userId(toolContext));
        return diaryRoutineService.removeItemFromRoutine(routineId, groupId, userId(toolContext));
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

    @Tool(description = "Schedule a routine on week days (Monday..Sunday, any letter case; Portuguese "
            + "day names work too). A day can only have one routine — scheduling over an already-taken "
            + "day moves that day to this routine")
    ScheduleResponseDTO createUserSchedule(CreateScheduleDTO schedule, ToolContext toolContext) {
        log.info("AI agent is creating a schedule for user: {}", userId(toolContext));
        return ScheduleResponseDTO.from(scheduleService.create(valid(schedule), userId(toolContext)));
    }

    @Tool(description = "Update a schedule's week days by scheduleId")
    ScheduleResponseDTO updateUserSchedule(UpdateScheduleDTO schedule, ToolContext toolContext) {
        log.info("AI agent is updating schedule {} for user: {}", schedule.scheduleId(), userId(toolContext));
        return ScheduleResponseDTO.from(scheduleService.update(valid(schedule), userId(toolContext)));
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
        return diaryRoutineService.checkAndUncheckGroup(valid(checkRequest), userId(toolContext));
    }

    @Tool(description = "Skip or unskip ONE routine item on a date (skipped items don't hurt the "
            + "streak). Same shape as checkRoutineItem plus skip=true|false")
    RefreshUiDTO skipRoutineItem(SkipGroupRequestDTO skipRequest, ToolContext toolContext) {
        log.info("AI agent is skipping a routine item on routine {} for user: {}",
                skipRequest.routineId(), userId(toolContext));
        return diaryRoutineService.skipOrUnskipGroup(valid(skipRequest), userId(toolContext));
    }

    // Focus Mode
    //
    // Micro-tasks hang off a routine ENTRY (habitGroup/taskGroup), the same id check and skip
    // take, and they are always "today" — the server files them under the owner's own day, so no
    // tool here accepts a date for a write.
    //
    // Cycles are deliberately read-only. A cycle is the record that somebody actually sat through
    // a timer, and the client only reports one that ran out; a tool that writes them would let the
    // agent invent history the person never lived. Same reasoning as the XP rule on check-in.

    @Tool(description = "Get the micro-tasks on ONE routine entry for today. itemGroupId is the "
            + "entry id (habitGroupId or taskGroupId from getUserRoutines), NOT a habit or task id. "
            + "Note this read also materialises the user's pinned micro-tasks onto that entry, so "
            + "it can create rows: call it because the user asked about the list, not to peek")
    List<FocusMicroTaskResponseDTO> getItemMicroTasks(
            @ToolParam(description = "The routine entry: its habitGroupId or taskGroupId from getUserRoutines")
            UUID itemGroupId,
            ToolContext toolContext) {
        UUID item = requireItemGroup(itemGroupId, toolContext);
        log.info("AI agent is reading micro-tasks of item {} for user: {}", item, userId(toolContext));
        return focusService.listMicroTasks(loadUser(toolContext), item);
    }

    @Tool(description = "Get everything Focus Mode recorded on one day: the timer cycles that "
            + "completed, and every micro-task with whether it was done. Read-only — unlike "
            + "getItemMicroTasks it creates nothing. Use it for questions about how a day went")
    FocusDayResponseDTO getFocusDay(
            @ToolParam(description = "The day as YYYY-MM-DD. Omit for today", required = false)
            String date,
            ToolContext toolContext) {
        User user = loadUser(toolContext);
        LocalDate day = parseDate(date, "date");
        if (day == null) {
            day = UserDateResolver.today(user);
        }
        log.info("AI agent is reading the focus day {} for user: {}", day, userId(toolContext));
        return focusService.getDay(user, day);
    }

    @Tool(description = "Add a micro-task to ONE routine entry, for today. Asking twice for the "
            + "same name returns the existing one instead of duplicating it. pinned=true makes the "
            + "NAME a template that is re-created on every entry the user moves to until it is "
            + "unpinned — only pin when the user asks for something they want kept")
    FocusMicroTaskResponseDTO addMicroTask(
            @ToolParam(description = "The routine entry: its habitGroupId or taskGroupId from getUserRoutines")
            UUID itemGroupId,
            @ToolParam(description = "What to do, in the user's own words. Max 80 characters")
            String name,
            @ToolParam(description = "Keep this name for every entry from now on. Defaults to false", required = false)
            Boolean pinned,
            ToolContext toolContext) {
        UUID item = requireItemGroup(itemGroupId, toolContext);
        log.info("AI agent is adding a micro-task to item {} for user: {}", item, userId(toolContext));
        return focusService.addMicroTask(loadUser(toolContext),
                valid(new CreateMicroTaskRequestDTO(item, name, Boolean.TRUE.equals(pinned))));
    }

    @Tool(description = "Tick or untick ONE micro-task — it toggles, so calling it on a done "
            + "micro-task marks it not done. The id comes from getItemMicroTasks or getFocusDay. "
            + "No XP is involved, unlike checking a routine item")
    FocusMicroTaskResponseDTO toggleMicroTask(
            @ToolParam(description = "The micro-task id from getItemMicroTasks or getFocusDay")
            UUID microTaskId,
            ToolContext toolContext) {
        log.info("AI agent is toggling micro-task {} for user: {}", microTaskId, userId(toolContext));
        return focusService.toggleMicroTask(loadUser(toolContext), microTaskId);
    }

    @Tool(description = "Keep a micro-task for next time, or stop keeping it. Pinning applies to "
            + "the NAME and not to this one row: pinning \"stretch\" pins it on every entry and "
            + "every day, and unpinning it anywhere unpins it everywhere. Tell the user that is "
            + "what will happen before calling this")
    FocusMicroTaskResponseDTO pinMicroTask(
            @ToolParam(description = "The micro-task id from getItemMicroTasks or getFocusDay")
            UUID microTaskId,
            @ToolParam(description = "true to keep the name from now on, false to stop keeping it")
            Boolean pinned,
            ToolContext toolContext) {
        if (pinned == null) {
            throw new IllegalArgumentException(
                    "Missing pinned: send true to keep this micro-task's name, false to stop keeping it");
        }
        log.info("AI agent is setting pinned={} on micro-task {} for user: {}",
                pinned, microTaskId, userId(toolContext));
        return focusService.setPinned(loadUser(toolContext), microTaskId, pinned);
    }

    @Tool(description = "Delete ONE micro-task from today. If it was pinned this ALSO stops "
            + "keeping the name everywhere, because a pinned row that was merely deleted comes "
            + "straight back on the next read. Confirm with the user first")
    Map<String, String> deleteMicroTask(
            @ToolParam(description = "The micro-task id from getItemMicroTasks or getFocusDay")
            UUID microTaskId,
            ToolContext toolContext) {
        log.info("AI agent is deleting micro-task {} for user: {}", microTaskId, userId(toolContext));
        focusService.deleteMicroTask(loadUser(toolContext), microTaskId);
        return Map.of("success", "Micro-task deleted successfully");
    }

    @Tool(description = "Reorder the micro-tasks on ONE routine entry. Send the entry's micro-task "
            + "ids in the order they should now be in, the WHOLE list as getItemMicroTasks "
            + "returned it. Ids that do not belong to the entry are ignored, and anything left out "
            + "keeps its relative order after the ones you sent")
    List<FocusMicroTaskResponseDTO> reorderMicroTasks(
            @ToolParam(description = "The routine entry: its habitGroupId or taskGroupId from getUserRoutines")
            UUID itemGroupId,
            @ToolParam(description = "Every micro-task id of that entry, in the order they should be in")
            List<UUID> ids,
            ToolContext toolContext) {
        UUID item = requireItemGroup(itemGroupId, toolContext);
        log.info("AI agent is reordering the micro-tasks of item {} for user: {}", item, userId(toolContext));
        return focusService.reorderMicroTasks(loadUser(toolContext),
                valid(new ReorderMicroTasksRequestDTO(item, ids)));
    }

    // User configuration
    // Frontend-owned catalogs (packages/theme listOfThemes.ts, packages/state
    // dashboard/widgets.ts) — same vendoring precedent as AiIconCatalog.
    private static final List<String> AVAILABLE_THEMES = List.of(
            "beYou", "beYouDark", "Sunset", "Amethyst", "Midnight",
            "Cyberpunk", "Mocha", "Polar", "Late Latte");
    private static final List<String> AVAILABLE_WIDGETS = List.of(
            "worstArea", "constance", "betterArea", "dailyProgress",
            "fastTips", "levelProgress", "categoryBalance");

    @Tool(description = "Get the user's current configuration (name, profile phrase, theme, language, "
            + "timezone, streak/constance mode, XP decay strategy, dashboard widgets) plus the valid "
            + "options for theme and widgets")
    Map<String, Object> getUserConfiguration(ToolContext toolContext) {
        log.info("AI agent is reading configuration for user: {}", userId(toolContext));
        User user = userService.findUserById(userId(toolContext));
        Map<String, Object> current = new java.util.HashMap<>();
        current.put("name", user.getName());
        current.put("perfilPhrase", user.getPerfilPhrase());
        current.put("perfilPhraseAuthor", user.getPerfilPhraseAuthor());
        current.put("theme", user.getThemeInUse());
        current.put("language", user.getLanguageInUse());
        current.put("timezone", user.getTimezone());
        current.put("constanceConfiguration", user.getConstanceConfiguration());
        current.put("xpDecayStrategy", user.getXpDecayStrategy());
        current.put("widgetsInUse", user.getWidgetsIdInUse());
        return Map.of(
                "currentConfiguration", current,
                "availableThemes", AVAILABLE_THEMES,
                "availableWidgets", AVAILABLE_WIDGETS,
                "availableLanguages", List.of("en", "pt"));
    }

    @Tool(description = "Update the user's configuration. PATCH semantics: only send the fields to "
            + "change, omit the rest. theme/widgetsId must come from getUserConfiguration's available "
            + "options (widgetsId REPLACES the whole widget list, in display order); language is en|pt; "
            + "timezone is an IANA zone id. Do not change name/photo unless explicitly asked")
    Map<String, String> updateUserConfiguration(UserEditDTO configUpdate, ToolContext toolContext) {
        log.info("AI agent is updating configuration for user: {}", userId(toolContext));
        userService.editUser(valid(configUpdate), userId(toolContext));
        return Map.of("success", "Configuration updated");
    }

    // Feedback
    @Tool(description = "Send feedback to the Beyou team on the user's behalf (bug report, feature "
            + "request or anything else). Only call when the user explicitly asks to send feedback, "
            + "and confirm the final text with them first — the body must be the user's words, not "
            + "your own summary. The user gets an acknowledgement email")
    Map<String, String> submitUserFeedback(
            @ToolParam(description = "BUG | FEATURE_REQUEST | OTHER") FeedbackCategory category,
            @ToolParam(description = "The feedback text, in the user's own words") String body,
            ToolContext toolContext) {
        log.info("AI agent is submitting feedback for user: {}", userId(toolContext));
        FeedbackContextDTO context = new FeedbackContextDTO(
                currentPage(toolContext), null, "agent", null, null);
        feedbackService.submitFeedback(
                valid(new CreateFeedbackRequestDTO(category, body, context)),
                userId(toolContext));
        return Map.of("success", "Feedback submitted to the Beyou team");
    }
}
