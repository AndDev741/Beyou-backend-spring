package beyou.beyouapp.backend.exceptions.routine;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class ScheduleNotFoundException extends BusinessException {
    public ScheduleNotFoundException(String message){
        super(ErrorKey.SCHEDULE_NOT_FOUND, message);
    }
}
