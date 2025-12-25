package beyou.beyouapp.backend.domain.task.dto;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import beyou.beyouapp.backend.domain.category.dto.CategoryMiniDTO;

public record TaskResponseDTO(
        UUID id,
        String name,
        String description,
        String iconId,
        Integer importance,
        Integer difficulty,
        Map<UUID,CategoryMiniDTO> categories,
        boolean oneTimeTask,
        LocalDate markedToDelete,
        LocalDate createdAt,
        LocalDate updatedAt
) {
}
