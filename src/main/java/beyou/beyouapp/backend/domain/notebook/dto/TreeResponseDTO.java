package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.List;

/** A topic's whole sidebar in one response. */
public record TreeResponseDTO(PageRefDTO topic, List<TreeItemDTO> items, int sourcesCount, int cardsDue) {
}
