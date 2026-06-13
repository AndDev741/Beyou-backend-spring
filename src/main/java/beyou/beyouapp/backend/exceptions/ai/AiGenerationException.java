package beyou.beyouapp.backend.exceptions.ai;

/**
 * Thrown when the AI provider is unreachable, times out, or returns
 * unparseable content after retry. Mapped to HTTP 503 by the
 * GlobalExceptionHandler (the client should try again later).
 */
public class AiGenerationException extends RuntimeException {

    public AiGenerationException(String message) {
        super(message);
    }

    public AiGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
