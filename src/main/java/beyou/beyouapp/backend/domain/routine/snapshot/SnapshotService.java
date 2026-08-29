package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.focus.CycleKind;
import beyou.beyouapp.backend.domain.focus.FocusCycle;
import beyou.beyouapp.backend.domain.focus.FocusCycleRepository;
import beyou.beyouapp.backend.domain.focus.FocusMicroTask;
import beyou.beyouapp.backend.domain.focus.FocusMicroTaskRepository;
import beyou.beyouapp.backend.domain.focus.dto.FocusCycleResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final FocusCycleRepository focusCycleRepository;
    private final FocusMicroTaskRepository focusMicroTaskRepository;

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
    public List<SnapshotResponseDTO> getSnapshotsForDay(LocalDate date, UUID userId) {
        List<RoutineSnapshot> snapshots = snapshotRepository.findAllByUserIdAndSnapshotDate(userId, date);
        if (snapshots.isEmpty()) return List.of();
        // The focus rows are keyed by (user, day), the same pair for every snapshot in this list,
        // so they are read ONCE here and handed down. Mapping through the single-snapshot overload
        // re-read them per routine, which put a 2N back on the very endpoint built to remove an N+1.
        FocusDay focus = readFocusDay(userId, date);
        return snapshots.stream()
                .map(snapshot -> toResponseDTO(snapshot, focus))
                .collect(Collectors.toList());
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

    /**
     * The snapshot with the Focus Mode's day joined in.
     *
     * <p>Two reads for the day, then joined in memory on {@code originalGroupId}: one query for the
     * micro-tasks and one for the cycles, whatever the length of the routine. The alternative — a
     * lookup per check row — is an N+1 sized by the routine, on the history screen, which is exactly
     * where a productive person has the most rows.
     *
     * <p>Cycles that ran on none of this routine's items still appear on it when they ran on NO item
     * at all: a pomodoro started with nothing selected happened on this day and has nowhere else to
     * show. A cycle on some OTHER routine's item is left to that routine's snapshot.
     */
    public SnapshotResponseDTO toResponseDTO(RoutineSnapshot snapshot) {
        return toResponseDTO(snapshot, readFocusDay(snapshot.getUser().getId(), snapshot.getSnapshotDate()));
    }

    /** The Focus Mode's rows for one (user, day), read once and shared by every snapshot of that day. */
    private record FocusDay(
            Map<UUID, List<FocusMicroTaskResponseDTO>> microTasksByGroup,
            Map<UUID, Long> pomodorosByGroup,
            List<FocusCycle> cycles) {
    }

    private FocusDay readFocusDay(UUID userId, LocalDate day) {
        Map<UUID, List<FocusMicroTaskResponseDTO>> microTasksByGroup =
                focusMicroTaskRepository.findDay(userId, day).stream()
                        .collect(Collectors.groupingBy(
                                t -> t.getItemGroup().getId(),
                                Collectors.mapping(FocusMicroTaskResponseDTO::from, Collectors.toList())));

        List<FocusCycle> dayCycles = focusCycleRepository.findDay(userId, day);
        Map<UUID, Long> pomodorosByGroup = dayCycles.stream()
                .filter(c -> c.getItemGroup() != null && c.getKind() == CycleKind.POMODORO)
                .collect(Collectors.groupingBy(c -> c.getItemGroup().getId(), Collectors.counting()));
        return new FocusDay(microTasksByGroup, pomodorosByGroup, dayCycles);
    }

    private SnapshotResponseDTO toResponseDTO(RoutineSnapshot snapshot, FocusDay focus) {
        Map<UUID, List<FocusMicroTaskResponseDTO>> microTasksByGroup = focus.microTasksByGroup();
        Map<UUID, Long> pomodorosByGroup = focus.pomodorosByGroup();
        List<FocusCycle> dayCycles = focus.cycles();

        Set<UUID> groupsInThisRoutine = new HashSet<>();
        List<SnapshotCheckResponseDTO> checkDTOs = snapshot.getChecks().stream()
                .map(check -> {
                    UUID group = check.getOriginalGroupId();
                    if (group != null) groupsInThisRoutine.add(group);
                    return toCheckResponseDTO(
                            check,
                            group == null ? List.of() : microTasksByGroup.getOrDefault(group, List.of()),
                            group == null ? 0 : pomodorosByGroup.getOrDefault(group, 0L).intValue());
                })
                .collect(Collectors.toList());

        List<FocusCycleResponseDTO> cycles = dayCycles.stream()
                .filter(c -> c.getItemGroup() == null || groupsInThisRoutine.contains(c.getItemGroup().getId()))
                .map(FocusCycleResponseDTO::from)
                .toList();

        return new SnapshotResponseDTO(
                snapshot.getId(),
                snapshot.getRoutine().getId(),
                snapshot.getSnapshotDate(),
                snapshot.getRoutineName(),
                snapshot.getRoutineIconId(),
                snapshot.isCompleted(),
                snapshot.getStructureJson(),
                checkDTOs,
                cycles
        );
    }

    public SnapshotCheckResponseDTO toCheckResponseDTO(SnapshotCheck check) {
        return toCheckResponseDTO(check, List.of(), 0);
    }

    private SnapshotCheckResponseDTO toCheckResponseDTO(
            SnapshotCheck check, List<FocusMicroTaskResponseDTO> microTasks, int pomodoros) {
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
                check.getXpGenerated(),
                microTasks,
                pomodoros
        );
    }
}
