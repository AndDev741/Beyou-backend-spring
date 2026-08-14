package beyou.beyouapp.backend.unit.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.BeforeEach;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskMapper;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.task.TaskNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
public class TaskServiceUnitTest {
    @Mock
    TaskRepository taskRepository;

    @Mock
    CategoryService categoryService;

    @Mock
    UserRepository userRepository;

    @Mock
    DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    UserCacheEvictService userCacheEvictService;

    @Mock
    EntityCheckDayRepository entityCheckDayRepository;

    TaskMapper taskMapper = new TaskMapper();

    TaskService taskService;

    UUID taskId = UUID.randomUUID();
    Task newTask = new Task();
    List<Task> tasks = new ArrayList<Task>(List.of(newTask));
    UUID userId = UUID.randomUUID();
    User user = new User();

    @BeforeEach
    void setup() {
        taskService = new TaskService(taskRepository, userRepository, categoryService, diaryRoutineRepository, taskMapper, userCacheEvictService, entityCheckDayRepository);
    }

    @Test
    public void shouldGetTaskSuccessfully(){
        newTask.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(newTask));

        Task task = taskService.getTask(taskId);

        assertEquals(taskId, task.getId());
    }

    @Test
    public void shoulGetAllTheTasksFromUser(){
        user.setId(userId);

        when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.of(tasks));

        List<TaskResponseDTO> getTasks = taskService.getAllTasks(userId);

        assertEquals(1, getTasks.size());
    }

    @Test
    public void shouldCreateATaskSuccessfully(){
        List<UUID> categoriesId= new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
        Category category = new Category();
        category.setId(categoriesId.get(0));

        CreateTaskRequestDTO createTaskDTO = new CreateTaskRequestDTO(
        "taskName",
        "Task description", 
        "IconId",
        2, 
        2,
        categoriesId,
        false);

        ResponseEntity<Map<String, String>> successMessage = ResponseEntity.ok().body(Map.of("success", "Task created Successfully"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(categoryService.getCategory(categoriesId.get(0), userId)).thenReturn(category);
        
        ResponseEntity<Map<String, String>> responseMessage = taskService.createTask(createTaskDTO, userId);

        assertEquals(successMessage, responseMessage);
    }

    @Test
    public void shouldCreateACategoryWithoutTheOptionalAttributes(){
        CreateTaskRequestDTO createTaskDTO = new CreateTaskRequestDTO(
        "taskName",
        "Task description", 
        "IconId",
        null,
        null,
        null,
        false);

        ResponseEntity<Map<String, String>> successMessage = ResponseEntity.ok().body(Map.of("success", "Task created Successfully"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        
        ResponseEntity<Map<String, String>> responseMessage = taskService.createTask(createTaskDTO, userId);

        assertEquals(successMessage, responseMessage);
    }

    @Test
    public void shouldEditATaskSuccessfully(){
        user.setId(userId);
        UUID taskId = UUID.randomUUID();
        Task taskToEdit = new Task();
        taskToEdit.setId(taskId);
        taskToEdit.setUser(user);
        taskToEdit.setName("oldName");

        EditTaskRequestDTO editTaskRequestDTO = new EditTaskRequestDTO(taskId, "newName", null, null, null, null, null, false);
        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok().body(Map.of("success", "Task edited successfully"));

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskToEdit));

        ResponseEntity<Map<String, String>> editTaskResponse = taskService.editTask(editTaskRequestDTO, userId);
        
        assertEquals(successResponse, editTaskResponse);
    }

    @Test
    public void shouldDeleteSuccessfullyATask(){
        user.setId(userId);
        Task taskToDelete = new Task();
        taskToDelete.setId(taskId);
        taskToDelete.setUser(user);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskToDelete));

        ResponseEntity<Map<String, String>> deleteTaskResponse = taskService.deleteTask(taskId, userId);

        assertEquals(ResponseEntity.ok(Map.of("success", "Task deleted Successfully!")), deleteTaskResponse);
    }

    /**
     * R15: cleanup dates resolve in the owning user's timezone. A one-time task marked on the
     * owner's local day must survive until that local day has passed — the server's calendar day
     * has no say. Uses an owner zone whose local date provably differs from the server's right
     * now (think an America/Los_Angeles user against a UTC server, but deterministic at any hour).
     */
    @Test
    public void shouldOnlyDeleteMarkedTasksOnceTheOwnersLocalDayHasPassed() {
        ZoneId ownerZone = zoneWhoseTodayDiffersFromServer();
        user.setId(userId);
        user.setTimezone(ownerZone.getId());
        LocalDate ownerToday = LocalDate.now(ownerZone);

        Task markedToday = new Task();
        markedToday.setId(UUID.randomUUID());
        markedToday.setUser(user);
        markedToday.setMarkedToDelete(ownerToday);

        Task markedYesterday = new Task();
        markedYesterday.setId(UUID.randomUUID());
        markedYesterday.setUser(user);
        markedYesterday.setMarkedToDelete(ownerToday.minusDays(1));

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of());

        taskService.deleteAllMarked(new ArrayList<>(List.of(markedToday, markedYesterday)), userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskRepository).deleteAll(captor.capture());
        assertEquals(List.of(markedYesterday), captor.getValue(),
                "Only the task whose owner-local day already passed may be deleted");
    }

    /**
     * R8/KTD24 — the task's day history goes with the task, exactly as a habit's does.
     */
    @Test
    public void shouldDeleteTheTasksCheckDayHistoryWhenDeletingTheTask(){
        user.setId(userId);
        Task taskToDelete = new Task();
        taskToDelete.setId(taskId);
        taskToDelete.setUser(user);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskToDelete));

        taskService.deleteTask(taskId, userId);

        verify(entityCheckDayRepository).deleteAllByOwner(CheckDayOwnerType.TASK, taskId);
    }

    /**
     * A task still held by a routine is refused, so nothing of its history is touched.
     */
    @Test
    public void shouldNotTouchTheHistoryWhenTheTaskDeleteIsRefused(){
        user.setId(userId);
        Task taskToDelete = new Task();
        taskToDelete.setId(taskId);
        taskToDelete.setUser(user);

        DiaryRoutine routine = new DiaryRoutine();
        RoutineSection section = new RoutineSection();
        TaskGroup group = new TaskGroup();
        group.setTask(taskToDelete);
        section.setTaskGroups(new ArrayList<>(List.of(group)));
        routine.setRoutineSections(new ArrayList<>(List.of(section)));

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskToDelete));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskService.deleteTask(taskId, userId));

        assertEquals(ErrorKey.TASK_IN_ROUTINE, exception.getErrorKey());
        verifyNoInteractions(entityCheckDayRepository);
    }

    /**
     * The cleanup path clears history for every task it actually removes, and only those.
     * Today every such task is a one-time task with no rows to clear, so this asserts the
     * call is wired rather than that rows disappear — the point is that the delete site does
     * not carry its own copy of the "one-time tasks are exempt" rule.
     */
    @Test
    public void shouldDeleteTheCheckDayHistoryOfEveryTaskTheCleanupActuallyRemoves() {
        ZoneId ownerZone = zoneWhoseTodayDiffersFromServer();
        user.setId(userId);
        user.setTimezone(ownerZone.getId());
        LocalDate ownerToday = LocalDate.now(ownerZone);

        Task markedToday = new Task();
        markedToday.setId(UUID.randomUUID());
        markedToday.setUser(user);
        markedToday.setMarkedToDelete(ownerToday);

        Task markedYesterday = new Task();
        markedYesterday.setId(UUID.randomUUID());
        markedYesterday.setUser(user);
        markedYesterday.setMarkedToDelete(ownerToday.minusDays(1));

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of());

        taskService.deleteAllMarked(new ArrayList<>(List.of(markedToday, markedYesterday)), userId);

        verify(entityCheckDayRepository)
                .deleteAllByOwner(CheckDayOwnerType.TASK, markedYesterday.getId());
        verify(entityCheckDayRepository, never())
                .deleteAllByOwner(CheckDayOwnerType.TASK, markedToday.getId());
    }

    /**
     * UTC+14 and UTC-12 sit 26 hours apart, so their local dates never coincide — at any
     * instant at least one of them is on a different calendar day than the server.
     */
    private static ZoneId zoneWhoseTodayDiffersFromServer() {
        LocalDate serverToday = LocalDate.now();
        for (String zoneId : List.of("Etc/GMT-14", "Etc/GMT+12")) {
            ZoneId zone = ZoneId.of(zoneId);
            if (!LocalDate.now(zone).equals(serverToday)) {
                return zone;
            }
        }
        throw new IllegalStateException("No zone differed from the server's day — impossible by construction");
    }

    //Exceptions

     @Test
    public void shouldThrowExceptionWhenTaskNotFound() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThrows(TaskNotFound.class, () -> taskService.getTask(taskId));
    }

    @Test
    public void shouldThrowExceptionWhenUserNotFoundGettingTasks() {
        when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.empty());
        assertThrows(UserNotFound.class, () -> taskService.getAllTasks(userId));
    }

    @Test
    public void shouldThrowExceptionWhenUserNotFoundCreatingTask() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        CreateTaskRequestDTO createTaskDTO = new CreateTaskRequestDTO("task", "desc", "icon", 1, 1, null, false);
        assertThrows(UserNotFound.class, () -> taskService.createTask(createTaskDTO, userId));
    }

    @Test
    public void shouldThrowExceptionWhenEditingTaskThatDoesNotExist() {
        EditTaskRequestDTO editTaskRequestDTO = new EditTaskRequestDTO(taskId, "newName", null, null, null, null, null, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThrows(TaskNotFound.class, () -> taskService.editTask(editTaskRequestDTO, userId));
    }

    @Test
    public void shouldThrowExceptionWhenEditingTaskOfAnotherUser() {
        UUID anotherUserId = UUID.randomUUID();
        User user = new User();
        user.setId(anotherUserId);
        Task task = new Task();
        task.setId(taskId);
        task.setUser(user);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        EditTaskRequestDTO editTaskRequestDTO = new EditTaskRequestDTO(taskId, "newName", null, null, null, null, null, false);
        
        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskService.editTask(editTaskRequestDTO, userId));
        assertEquals(ErrorKey.TASK_NOT_OWNED, exception.getErrorKey());
    }

    @Test
    public void shouldThrowExceptionWhenDeletingTaskThatDoesNotExist() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThrows(TaskNotFound.class, () -> taskService.deleteTask(taskId, userId));
    }

    @Test
    public void shouldThrowExceptionWhenDeletingTaskOfAnotherUser() {
        UUID anotherUserId = UUID.randomUUID();
        User user = new User();
        user.setId(anotherUserId);
        Task task = new Task();
        task.setId(taskId);
        task.setUser(user);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        
        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskService.deleteTask(taskId, userId));
        assertEquals(ErrorKey.TASK_NOT_OWNED, exception.getErrorKey());
    }
}
