package beyou.beyouapp.backend.domain.notebook.diagram.dto;

import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;

/**
 * A generated diagram, already cleaned.
 *
 * @param fromNotes the request was grounded and the page had notes or sources to read, so the
 *                  client can say where the diagram came from
 */
public record DiagramResultDTO(DiagramSpec diagram, boolean fromNotes) {
}
