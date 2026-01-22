package beyou.beyouapp.backend.domain.common.DTO;

import java.util.List;

public record RefreshUiDTO(
    RefreshUserDTO refreshUser,
    List<RefreshObjectDTO> refreshCategories,
    RefreshObjectDTO refreshHabit,
    RefreshItemCheckedDTO refreshItemChecked
) {
    
}