package beyou.beyouapp.backend.exceptions.task;

public class TaskNotFound extends RuntimeException {
    
    public TaskNotFound(String message){
        super(message);
    }
}
