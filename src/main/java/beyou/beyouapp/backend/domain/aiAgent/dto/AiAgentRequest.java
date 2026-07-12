package beyou.beyouapp.backend.domain.aiAgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * currentPage: app route the user is on when sending (e.g. "/habits") —
 * optional.
 */
public record AiAgentRequest(
                @NotBlank @Size(max = 4000) String userInput,
                @Size(max = 200) String currentPage) {
}
