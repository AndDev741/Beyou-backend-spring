package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@RestController
@RequestMapping("/task")
public class TaskController {
    TaskService taskService;
    AuthenticatedUser authenticatedUser;

    public TaskController(TaskService taskService, AuthenticatedUser authenticatedUser){
        this.taskService = taskService;
        this.authenticatedUser = authenticatedUser;
    }

    @GetMapping
    public List<TaskResponseDTO> getTasks(){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return taskService.getAllTasks(userAuth.getId());
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createTask(@RequestBody CreateTaskRequestDTO taskRequestDTO){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return taskService.createTask(taskRequestDTO, userAuth.getId());
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> editTask(@RequestBody EditTaskRequestDTO editTaskRequestDTO){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return taskService.editTask(editTaskRequestDTO, userAuth.getId());
    }

    @DeleteMapping(value = "/{taskId}")
    public ResponseEntity<Map<String, String>> deleteTask(@PathVariable UUID taskId){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return taskService.deleteTask(taskId, userAuth.getId());
    }
}
