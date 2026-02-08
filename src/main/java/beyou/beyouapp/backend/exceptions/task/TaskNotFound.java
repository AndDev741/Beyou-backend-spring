package beyou.beyouapp.backend.exceptions.task;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class TaskNotFound extends BusinessException {
    
    public TaskNotFound(String message){
        super(ErrorKey.TASK_NOT_FOUND, message);
    }
}
