package beyou.beyouapp.backend.user.dto;

import java.util.List;

import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserEditDTO(
    @Size(max = 256, message = "Name is too long")
    @Pattern(regexp = ".*\\S.*\\S.*", message = "Name require a minimum of 2 characters")
    String name,
    @Size(max = 2048, message = "Photo URL is too long")
    @Pattern(regexp = "(?i)^(?:$|https?://.+)", message = "Photo URL is invalid")
    String photo,
    @Size(max = 256, message = "Phrase is too long")
    String phrase,
    @Size(max = 256, message = "Phrase author is too long")
    String phrase_author,
    List<String> widgetsId,
    String theme,
    ConstanceConfiguration constanceConfiguration,
    String language
) {
    
}
