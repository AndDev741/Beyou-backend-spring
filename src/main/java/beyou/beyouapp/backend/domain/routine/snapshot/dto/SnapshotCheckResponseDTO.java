package beyou.beyouapp.backend.domain.routine.snapshot.dto;

import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * One check row of a day's snapshot, with what the Focus Mode did on that item.
 *
 * <p>{@code microTasks} and {@code pomodoros} are joined on {@code originalGroupId}: the user asked
 * to see "each micro-task created for a task or habit of the routine" in the snapshot, and the check
 * row IS the task or habit of the routine, so this is where they attach.
 *
 * <p>The 12-argument constructor is kept for every caller that predates the Focus Mode. Server-built
 * only — never deserialised from a request body — so the overload is safe (Jackson and records can
 * pick the wrong constructor on the way IN, which is why request DTOs never get one).
 */
public record SnapshotCheckResponseDTO(
    UUID id, SnapshotItemType itemType, String itemName, String itemIconId,
    String sectionName, UUID originalGroupId, int difficulty, int importance,
    boolean checked, boolean skipped, LocalTime checkTime, double xpGenerated,
    List<FocusMicroTaskResponseDTO> microTasks, int pomodoros
) {
    public SnapshotCheckResponseDTO(
        UUID id, SnapshotItemType itemType, String itemName, String itemIconId,
        String sectionName, UUID originalGroupId, int difficulty, int importance,
        boolean checked, boolean skipped, LocalTime checkTime, double xpGenerated
    ) {
        this(id, itemType, itemName, itemIconId, sectionName, originalGroupId, difficulty, importance,
            checked, skipped, checkTime, xpGenerated, List.of(), 0);
    }
}
