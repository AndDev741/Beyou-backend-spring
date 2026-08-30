package beyou.beyouapp.backend.domain.aiAgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * currentPage: app route the user is on when sending (e.g. "/habits") —
 * optional.
 *
 * <p>selectedItemGroupId: the routine entry open in Focus Mode, when the user is in it. A String
 * and not a UUID on purpose: it is a convenience field the client fills from what it happens to be
 * showing, and a message must not fail to send because that value was empty or stale. The service
 * parses it and drops anything that is not an id, the same posture {@code UserDateResolver} takes
 * with a client-claimed timezone.
 */
public record AiAgentRequest(
                @NotBlank @Size(max = 4000) String userInput,
                @Size(max = 200) String currentPage,
                @Size(max = 64) String selectedItemGroupId) {
}
