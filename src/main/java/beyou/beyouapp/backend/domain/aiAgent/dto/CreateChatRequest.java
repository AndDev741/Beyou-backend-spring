package beyou.beyouapp.backend.domain.aiAgent.dto;

import jakarta.validation.constraints.Size;

public record CreateChatRequest(@Size(max = 255) String title) {
}
