package beyou.beyouapp.backend.exceptions.habit;

public class HabitNotFound extends RuntimeException {
    public HabitNotFound(String message){
        super(message);
    }
}
