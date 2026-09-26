package beyou.beyouapp.backend.domain.goal.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * The state to be in, not a toggle. A double tap on a toggle archives and restores in one
 * breath; "archived: true" sent twice is simply archived.
 */
public record ArchiveGoalRequestDTO(
        @NotNull UUID goalId,
        @NotNull Boolean archived
) {
}
