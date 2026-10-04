package beyou.beyouapp.backend.domain.notebook.study.dto;

/** The question as stored and the answer it got, so the client appends both in one go. */
public record ChatTurnDTO(ChatMessageDTO question, ChatMessageDTO answer) {
}
