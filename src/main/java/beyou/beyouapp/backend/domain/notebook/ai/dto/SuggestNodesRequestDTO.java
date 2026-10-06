package beyou.beyouapp.backend.domain.notebook.ai.dto;

/** {@code fromSources}: ground the suggestions on the page's sources ("Board from sources"). */
public record SuggestNodesRequestDTO(boolean fromSources) {
}
