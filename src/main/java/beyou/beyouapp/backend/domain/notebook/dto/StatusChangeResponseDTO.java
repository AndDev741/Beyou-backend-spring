package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;

/**
 * What a status change did.
 *
 * @param changed   every page whose status moved, this one included, so every board on screen
 *                  can repaint without a refetch
 * @param xpEarned  XP paid by this change; zero when no page finished for the first time
 * @param refreshUi the usual repaint payload when XP was paid, else null
 */
public record StatusChangeResponseDTO(
        UUID pageId,
        NotebookStatus status,
        boolean statusManual,
        List<PageStatusDTO> changed,
        double xpEarned,
        RefreshUiDTO refreshUi) {
}
