package beyou.beyouapp.backend.exceptions.goal;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class GoalNotFound extends BusinessException {
    public GoalNotFound(String message) {
        super(ErrorKey.GOAL_NOT_FOUND, message);
    }
}
