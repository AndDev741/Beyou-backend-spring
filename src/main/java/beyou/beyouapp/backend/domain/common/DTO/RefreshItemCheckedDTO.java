package beyou.beyouapp.backend.domain.common.DTO;

import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.checks.BaseCheck;

public record RefreshItemCheckedDTO(
    UUID groupItemId,
    BaseCheck check
) {
    
}
