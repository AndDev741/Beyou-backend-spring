package beyou.beyouapp.backend.exceptions.user;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class UserNotFound extends BusinessException {
    public UserNotFound(String message) {
        super(ErrorKey.USER_NOT_FOUND, message);
    }
}
