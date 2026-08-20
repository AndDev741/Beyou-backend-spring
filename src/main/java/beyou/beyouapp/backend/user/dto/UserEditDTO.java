package beyou.beyouapp.backend.user.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import beyou.beyouapp.backend.user.enums.TimezoneSource;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserEditDTO(
    @Size(max = 256, message = "Name is too long")
    @Pattern(regexp = ".*\\S.*\\S.*", message = "Name require a minimum of 2 characters")
    String name,
    @Size(max = 2048, message = "Photo URL is too long")
    @Pattern(regexp = "(?i)^(?:$|https?://.+|/api/v1/user/photo/.+)", message = "Photo URL is invalid")
    String photo,
    @Size(max = 256, message = "Phrase is too long")
    String phrase,
    @Size(max = 256, message = "Phrase author is too long")
    String phrase_author,
    List<String> widgetsId,
    String theme,
    ConstanceConfiguration constanceConfiguration,
    String language,
    Boolean isTutorialCompleted,
    String timezone,
    /**
     * How the accompanying {@code timezone} was arrived at. Only {@code DETECTED} is
     * meaningful from a client, and it is a request rather than an instruction: the
     * server adopts it only while the account is still {@code DEFAULT}. Null and
     * {@code EXPLICIT} both mean "a person picked this"; {@code DEFAULT} is rejected,
     * since no client has any business resetting an account to "never answered".
     */
    TimezoneSource timezoneSource,
    XpDecayStrategy xpDecayStrategy
) {
    
}
