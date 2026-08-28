package beyou.beyouapp.backend.domain.focus.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import beyou.beyouapp.backend.domain.focus.FocusMicroTask;

public record FocusMicroTaskResponseDTO(
    UUID id,
    LocalDate date,
    UUID itemGroupId,
    String name,
    boolean pinned,
    Instant doneAt
) {
    public static FocusMicroTaskResponseDTO from(FocusMicroTask task) {
        return new FocusMicroTaskResponseDTO(
            task.getId(),
            task.getTaskDate(),
            task.getItemGroup().getId(),
            task.getName(),
            task.isPinned(),
            task.getDoneAt());
    }
}
