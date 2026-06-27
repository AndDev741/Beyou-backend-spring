package beyou.beyouapp.backend.integration.routine.snapshot;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a routine must also remove its snapshots (and their checks). Snapshots hold a
 * non-null FK to the routine with no DB-level cascade, so without explicit cleanup the delete
 * fails with a foreign-key violation. Runs against a real Postgres (Testcontainers) so the FK
 * is actually enforced.
 */
class DiaryRoutineDeleteSnapshotIT extends AbstractIntegrationTest {

    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private RoutineSnapshotRepository routineSnapshotRepository;
    @Autowired private SnapshotCheckRepository snapshotCheckRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void deletesRoutineWithItsSnapshotsAndChecks() {
        User user = new User();
        user.setName("Snapshot Delete IT");
        user.setEmail("snapshot-delete-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setName("Morning");
        routine.setIconId("");
        routine.setUser(user);
        routine = diaryRoutineRepository.saveAndFlush(routine);
        UUID routineId = routine.getId();

        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setRoutine(routine);
        snapshot.setUser(user);
        snapshot.setSnapshotDate(LocalDate.now().minusDays(1));
        snapshot.setRoutineName("Morning");
        snapshot.setStructureJson("[]");
        snapshot.setCompleted(false);

        SnapshotCheck check = new SnapshotCheck();
        check.setSnapshot(snapshot);
        check.setItemType(SnapshotItemType.HABIT);
        check.setItemName("Meditate");
        check.setSectionName("Wake");
        check.setDifficulty(1);
        check.setImportance(1);
        snapshot.getChecks().add(check);
        routineSnapshotRepository.saveAndFlush(snapshot);
        UUID checkId = check.getId();

        assertEquals(1, routineSnapshotRepository.findAllByRoutineId(routineId).size());

        // Must not throw a FK violation.
        diaryRoutineService.deleteDiaryRoutine(routineId, user.getId());

        assertTrue(diaryRoutineRepository.findById(routineId).isEmpty(), "routine should be deleted");
        assertTrue(routineSnapshotRepository.findAllByRoutineId(routineId).isEmpty(), "snapshots should be deleted");
        assertTrue(snapshotCheckRepository.findById(checkId).isEmpty(), "snapshot checks should be cascade-deleted");
    }
}
