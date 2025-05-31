package beyou.beyouapp.backend.exceptions.routine;

public class ScheduleNotFoundException extends RuntimeException {
    public ScheduleNotFoundException(String message){
        super(message);
    }
}
