package beyou.beyouapp.backend.domain.aiAgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameChatRequest(@NotBlank @Size(max = 255) String title) {
}
