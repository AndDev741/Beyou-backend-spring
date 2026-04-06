package beyou.beyouapp.backend.user.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class UserRegisteredEvent extends ApplicationEvent {
    private final String email;
    private final String verificationToken;
    private final String language;

    public UserRegisteredEvent(Object source, String email, String verificationToken, String language) {
        super(source);
        this.email = email;
        this.verificationToken = verificationToken;
        this.language = language;
    }
}
