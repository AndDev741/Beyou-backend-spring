package beyou.beyouapp.backend.domain.notebook.ai.dto;

/** A node the AI proposes for a board. Nothing is created until the person keeps it. */
public record SuggestedNodeDTO(String title, String why) {
}
