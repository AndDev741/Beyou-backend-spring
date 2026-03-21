package beyou.beyouapp.backend.domain.task;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaskCleanupScheduler {

    private final TaskRepository taskRepository;
    private final TaskService taskService;

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanupMarkedTasks() {
        List<Task> markedTasks = taskRepository.findAllByMarkedToDeleteBefore(LocalDate.now());
        if (markedTasks.isEmpty()) {
            return;
        }

        markedTasks.stream()
            .collect(Collectors.groupingBy(task -> task.getUser().getId()))
            .forEach((userId, tasks) -> {
                log.info("Cleaning up {} marked tasks for user {}", tasks.size(), userId);
                taskService.deleteAllMarked(tasks, userId);
            });
    }
}
