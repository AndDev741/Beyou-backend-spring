package beyou.beyouapp.backend.domain.focus.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import beyou.beyouapp.backend.domain.focus.CycleKind;
import beyou.beyouapp.backend.domain.focus.FocusCycle;

public record FocusCycleResponseDTO(
    UUID id,
    LocalDate date,
    UUID itemGroupId,
    CycleKind kind,
    Instant startedAt,
    Instant endedAt,
    int minutes
) {
    public static FocusCycleResponseDTO from(FocusCycle cycle) {
        return new FocusCycleResponseDTO(
            cycle.getId(),
            cycle.getCycleDate(),
            cycle.getItemGroup() != null ? cycle.getItemGroup().getId() : null,
            cycle.getKind(),
            cycle.getStartedAt(),
            cycle.getEndedAt(),
            cycle.getMinutes());
    }
}
