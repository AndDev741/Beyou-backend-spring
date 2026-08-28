package beyou.beyouapp.backend.domain.routine.snapshot.dto;

import beyou.beyouapp.backend.domain.focus.dto.FocusCycleResponseDTO;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A day's snapshot of one routine, plus the focus cycles run on it.
 *
 * <p>{@code focusCycles} carries every cycle of the day that ran on one of this routine's items, and
 * every cycle that ran on no item at all — a pomodoro started with nothing selected still happened
 * on this day and has nowhere else to appear.
 *
 * <p>The 8-argument constructor is kept for every caller that predates the Focus Mode. Server-built
 * only, so the overload is safe.
 */
public record SnapshotResponseDTO(
    UUID id, UUID routineId, LocalDate snapshotDate, String routineName, String routineIconId,
    boolean completed, @JsonRawValue String structure, List<SnapshotCheckResponseDTO> checks,
    List<FocusCycleResponseDTO> focusCycles
) {
    public SnapshotResponseDTO(
        UUID id, UUID routineId, LocalDate snapshotDate, String routineName, String routineIconId,
        boolean completed, String structure, List<SnapshotCheckResponseDTO> checks
    ) {
        this(id, routineId, snapshotDate, routineName, routineIconId, completed, structure, checks, List.of());
    }
}
