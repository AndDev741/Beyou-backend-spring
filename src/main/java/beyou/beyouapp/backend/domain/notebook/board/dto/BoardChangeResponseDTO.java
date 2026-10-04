package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageStatusDTO;

/**
 * What a board change did.
 *
 * @param node      the node created or changed; null for a delete
 * @param changed   pages whose status moved because of it (adding an unstarted node to a
 *                  finished page reopens it)
 * @param refreshUi present when removing a node finished a page and paid XP
 */
public record BoardChangeResponseDTO(BoardNodeDTO node, List<PageStatusDTO> changed, RefreshUiDTO refreshUi) {
}
