package beyou.beyouapp.backend.user.dto;

import java.util.List;

public record UserEditDTO(
    String name,
    String photo,
    String phrase,
    String phrase_author,
    List<String> widgetsId,
    String theme
) {
    
}
