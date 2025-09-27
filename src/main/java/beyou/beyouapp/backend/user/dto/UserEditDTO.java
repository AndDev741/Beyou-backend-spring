package beyou.beyouapp.backend.user.dto;

public record UserEditDTO(
    String name,
    String photo,
    String phrase,
    String phrase_author
) {
    
}
