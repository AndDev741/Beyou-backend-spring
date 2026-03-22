package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SnapshotCheckMigrator {

    private final EntityManager entityManager;

    @Transactional
    public void migrateChecks(DiaryRoutine routine, RoutineSnapshot snapshot, LocalDate date) {
        log.info("Migrating live checks to snapshot {} for routine {} on date {}",
                snapshot.getId(), routine.getId(), date);

        // Build map of originalGroupId -> SnapshotCheck from snapshot.getChecks()
        Map<UUID, SnapshotCheck> snapshotCheckByGroupId = snapshot.getChecks().stream()
                .filter(sc -> sc.getOriginalGroupId() != null)
                .collect(Collectors.toMap(
                        SnapshotCheck::getOriginalGroupId,
                        Function.identity(),
                        (existing, duplicate) -> existing
                ));

        List<UUID> habitGroupIds = new ArrayList<>();
        List<UUID> taskGroupIds = new ArrayList<>();

        for (RoutineSection section : routine.getRoutineSections()) {
            // Process habit groups
            if (section.getHabitGroups() != null) {
                for (HabitGroup habitGroup : section.getHabitGroups()) {
                    habitGroupIds.add(habitGroup.getId());

                    if (habitGroup.getHabitGroupChecks() == null) {
                        continue;
                    }

                    Optional<HabitGroupCheck> liveCheckOpt = habitGroup.getHabitGroupChecks().stream()
                            .filter(check -> date.equals(check.getCheckDate()))
                            .findFirst();

                    if (liveCheckOpt.isPresent()) {
                        HabitGroupCheck liveCheck = liveCheckOpt.get();
                        SnapshotCheck snapshotCheck = snapshotCheckByGroupId.get(habitGroup.getId());
                        if (snapshotCheck != null) {
                            snapshotCheck.setChecked(liveCheck.isChecked());
                            snapshotCheck.setSkipped(liveCheck.getSkipped() != null && liveCheck.getSkipped());
                            snapshotCheck.setCheckTime(liveCheck.getCheckTime());
                            snapshotCheck.setXpGenerated(liveCheck.getXpGenerated());
                        }
                    }
                }
            }

            // Process task groups
            if (section.getTaskGroups() != null) {
                for (TaskGroup taskGroup : section.getTaskGroups()) {
                    taskGroupIds.add(taskGroup.getId());

                    if (taskGroup.getTaskGroupChecks() == null) {
                        continue;
                    }

                    Optional<TaskGroupCheck> liveCheckOpt = taskGroup.getTaskGroupChecks().stream()
                            .filter(check -> date.equals(check.getCheckDate()))
                            .findFirst();

                    if (liveCheckOpt.isPresent()) {
                        TaskGroupCheck liveCheck = liveCheckOpt.get();
                        SnapshotCheck snapshotCheck = snapshotCheckByGroupId.get(taskGroup.getId());
                        if (snapshotCheck != null) {
                            snapshotCheck.setChecked(liveCheck.isChecked());
                            snapshotCheck.setSkipped(liveCheck.getSkipped() != null && liveCheck.getSkipped());
                            snapshotCheck.setCheckTime(liveCheck.getCheckTime());
                            snapshotCheck.setXpGenerated(liveCheck.getXpGenerated());
                        }
                    }
                }
            }
        }

        // Delete live checks via bulk JPQL
        if (!habitGroupIds.isEmpty()) {
            int deletedHabits = entityManager.createQuery(
                    "DELETE FROM HabitGroupCheck h WHERE h.habitGroup.id IN :ids AND h.checkDate = :date")
                    .setParameter("ids", habitGroupIds)
                    .setParameter("date", date)
                    .executeUpdate();
            log.debug("Deleted {} habit group checks for date {}", deletedHabits, date);
        }

        if (!taskGroupIds.isEmpty()) {
            int deletedTasks = entityManager.createQuery(
                    "DELETE FROM TaskGroupCheck t WHERE t.taskGroup.id IN :ids AND t.checkDate = :date")
                    .setParameter("ids", taskGroupIds)
                    .setParameter("date", date)
                    .executeUpdate();
            log.debug("Deleted {} task group checks for date {}", deletedTasks, date);
        }

        log.info("Check migration completed for snapshot {}", snapshot.getId());
    }
}
