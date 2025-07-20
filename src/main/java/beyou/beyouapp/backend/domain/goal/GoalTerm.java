package beyou.beyouapp.backend.domain.goal;

public enum GoalTerm {
    SHORT_TERM(1),
    MEDIUM_TERM(2),
    LONG_TERM(3);

    private final int value;

    GoalTerm(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}