package beyou.beyouapp.backend.domain.aiAgent.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;

public record ChatResponseDTO(
        UUID id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ChatResponseDTO from(Chat chat) {
        return new ChatResponseDTO(chat.getId(), chat.getTitle(), chat.getCreatedAt(), chat.getUpdatedAt());
    }
}
