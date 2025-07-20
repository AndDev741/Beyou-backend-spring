package beyou.beyouapp.backend.domain.goal;

public enum  GoalStatus {
    NOT_STARTED(1),
    IN_PROGRESS(2),
    COMPLETED(3);

    private final int value;

    GoalStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}