package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotCheckResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotMonthResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SnapshotService {

    private final RoutineSnapshotRepository snapshotRepository;
    private final SnapshotCheckRepository snapshotCheckRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final SnapshotStructureSerializer structureSerializer;

    @Transactional
    public RoutineSnapshot createSnapshot(DiaryRoutine routine, User user, LocalDate snapshotDate) {
        log.info("Creating snapshot for routine {} on date {}", routine.getId(), snapshotDate);

        String structureJson = structureSerializer.serializeStructure(routine);

        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setRoutine(routine);
        snapshot.setUser(user);
        snapshot.setSnapshotDate(snapshotDate);
        snapshot.setRoutineName(routine.getName());
        snapshot.setRoutineIconId(routine.getIconId());
        snapshot.setStructureJson(structureJson);
        snapshot.setCompleted(false);

        RoutineSnapshot savedSnapshot = snapshotRepository.save(snapshot);

        List<SnapshotCheck> checks = structureSerializer.createSnapshotChecks(routine, savedSnapshot);
        List<SnapshotCheck> savedChecks = snapshotCheckRepository.saveAll(checks);
        savedSnapshot.setChecks(savedChecks);

        log.info("Snapshot created with {} checks for routine {}", savedChecks.size(), routine.getId());
        return savedSnapshot;
    }

    @Transactional(readOnly = true)
    public SnapshotResponseDTO getSnapshot(UUID routineId, LocalDate date, UUID userId) {
        RoutineSnapshot snapshot = snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, date)
                .orElseThrow(() -> new BusinessException(ErrorKey.SNAPSHOT_NOT_FOUND,
                        "Snapshot not found for routine " + routineId + " on date " + date));

        if (!snapshot.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.SNAPSHOT_NOT_OWNED,
                    "User does not own the requested snapshot");
        }

        return toResponseDTO(snapshot);
    }

    @Transactional(readOnly = true)
    public SnapshotMonthResponseDTO getSnapshotDatesForMonth(UUID routineId, String month, UUID userId) {
        DiaryRoutine routine = diaryRoutineRepository.findById(routineId)
                .orElseThrow(() -> new BusinessException(ErrorKey.ROUTINE_NOT_FOUND,
                        "Routine not found with id " + routineId));

        if (!routine.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.ROUTINE_NOT_OWNED,
                    "User does not own the requested routine");
        }

        YearMonth yearMonth = YearMonth.parse(month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        List<LocalDate> dates = snapshotRepository.findSnapshotDatesByRoutineIdAndMonth(
                routineId, startDate, endDate);

        return new SnapshotMonthResponseDTO(dates);
    }

    public SnapshotResponseDTO toResponseDTO(RoutineSnapshot snapshot) {
        List<SnapshotCheckResponseDTO> checkDTOs = snapshot.getChecks().stream()
                .map(this::toCheckResponseDTO)
                .collect(Collectors.toList());

        return new SnapshotResponseDTO(
                snapshot.getId(),
                snapshot.getSnapshotDate(),
                snapshot.getRoutineName(),
                snapshot.getRoutineIconId(),
                snapshot.isCompleted(),
                snapshot.getStructureJson(),
                checkDTOs
        );
    }

    public SnapshotCheckResponseDTO toCheckResponseDTO(SnapshotCheck check) {
        return new SnapshotCheckResponseDTO(
                check.getId(),
                check.getItemType(),
                check.getItemName(),
                check.getItemIconId(),
                check.getSectionName(),
                check.getOriginalGroupId(),
                check.getDifficulty(),
                check.getImportance(),
                check.isChecked(),
                check.isSkipped(),
                check.getCheckTime(),
                check.getXpGenerated()
        );
    }
}
