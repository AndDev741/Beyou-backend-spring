package beyou.beyouapp.backend.user.dto;

import java.util.List;

import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

public record UserEditDTO(
    String name,
    String photo,
    String phrase,
    String phrase_author,
    List<String> widgetsId,
    String theme,
    ConstanceConfiguration constanceConfiguration
) {
    
}
