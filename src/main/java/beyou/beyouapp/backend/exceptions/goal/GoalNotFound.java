package beyou.beyouapp.backend.exceptions.goal;

public class GoalNotFound extends RuntimeException {
    public GoalNotFound(String message) {
        super(message);
    }
}