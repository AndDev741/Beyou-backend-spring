package beyou.beyouapp.backend.exceptions;

import java.util.Map;

public record ApiErrorResponse(
        String errorKey,
        String message,
        Map<String, String> details
) {}
