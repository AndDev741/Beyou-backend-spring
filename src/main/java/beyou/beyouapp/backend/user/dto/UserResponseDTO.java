package beyou.beyouapp.backend.user.dto;

public record UserResponseDTO(String name, String email, String phrase, String phrase_author,
                              int constance, String photo, boolean isGoogleAccount) {
}
