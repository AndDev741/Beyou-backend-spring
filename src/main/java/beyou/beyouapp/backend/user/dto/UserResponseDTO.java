package beyou.beyouapp.backend.user.dto;

import java.util.UUID;

public record UserResponseDTO(UUID id, String name, String email, String phrase, String phrase_author,
                              int constance, String photo, boolean isGoogleAccount) {
}
