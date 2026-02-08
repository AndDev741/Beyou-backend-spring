package beyou.beyouapp.backend.exceptions.habit;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class HabitNotFound extends BusinessException {
    public HabitNotFound(String message){
        super(ErrorKey.HABIT_NOT_FOUND, message);
    }
}
