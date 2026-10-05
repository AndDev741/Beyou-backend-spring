package beyou.beyouapp.backend.domain.notebook.ai.draft.dto;

/**
 * What the person decided about one drafted node, in the order of the draft's nodes.
 *
 * @param keep whether the node is created (an optional node starts unticked)
 * @param link for a node the person already has elsewhere: link that page instead of a copy
 */
public record DraftChoiceDTO(boolean keep, boolean link) {
}
