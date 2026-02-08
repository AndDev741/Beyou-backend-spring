package beyou.beyouapp.backend.exceptions.routine;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class DiaryRoutineNotFoundException extends BusinessException {
    public DiaryRoutineNotFoundException(String message) {
        super(ErrorKey.ROUTINE_NOT_FOUND, message);
    }
}
