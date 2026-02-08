package beyou.beyouapp.backend.exceptions;

public class BusinessException extends RuntimeException {
    private final ErrorKey errorKey;

    public BusinessException(ErrorKey errorKey) {
        super();
        this.errorKey = errorKey;
    }

    public BusinessException(ErrorKey errorKey, String message) {
        super(message);
        this.errorKey = errorKey;
    }

    public ErrorKey getErrorKey() {
        return errorKey;
    }
}
