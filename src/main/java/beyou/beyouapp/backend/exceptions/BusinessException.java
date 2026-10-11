package beyou.beyouapp.backend.exceptions;

import java.util.Map;

public class BusinessException extends RuntimeException {
    private final ErrorKey errorKey;
    /**
     * Facts the client needs to word the refusal, or null. Rides out as
     * {@code ApiErrorResponse.details}. Only for values the caller already owns: a refusal must
     * never become a way to read somebody else's data.
     */
    private final Map<String, String> details;

    public BusinessException(ErrorKey errorKey) {
        super();
        this.errorKey = errorKey;
        this.details = null;
    }

    public BusinessException(ErrorKey errorKey, String message) {
        super(message);
        this.errorKey = errorKey;
        this.details = null;
    }

    public BusinessException(ErrorKey errorKey, String message, Map<String, String> details) {
        super(message);
        this.errorKey = errorKey;
        this.details = details;
    }

    public ErrorKey getErrorKey() {
        return errorKey;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
