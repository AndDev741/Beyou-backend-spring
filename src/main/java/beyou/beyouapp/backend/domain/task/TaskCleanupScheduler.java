package beyou.beyouapp.backend.domain.task;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
        // Coarse pre-filter only. A task is deletable when markedToDelete < the OWNER's local
        // day, and the latest local day anywhere on earth is the one at UTC+18 (ZoneOffset.MAX),
        // so every deletable task necessarily has markedToDelete before that date. Widening here
        // over-fetches by at most a day; TaskService.deleteAllMarked applies the owner-timezone
        // decision. A server-zone LocalDate.now() would instead MISS tasks owned by users whose
        // day has already rolled over ahead of the server.
        LocalDate latestDayAnywhere = LocalDate.now(ZoneOffset.MAX);
        List<Task> markedTasks = taskRepository.findAllByMarkedToDeleteBefore(latestDayAnywhere);
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
