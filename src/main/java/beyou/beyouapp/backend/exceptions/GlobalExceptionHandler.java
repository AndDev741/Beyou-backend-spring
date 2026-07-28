package beyou.beyouapp.backend.exceptions;

import beyou.beyouapp.backend.exceptions.security.JwtNotFoundException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(JwtNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleJwtNotFoundException(JwtNotFoundException ex){
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.JWT_NOT_FOUND.name(), ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(RefreshTokenExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleRefreshTokenExpiredException(RefreshTokenExpiredException ex){
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.REFRESH_TOKEN_EXPIRED.name(), ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(RefreshTokenNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRefreshTokenNotFoundException(RefreshTokenNotFoundException ex){
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.REFRESH_TOKEN_NOT_FOUND.name(), ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(RefreshTokenDontMatchRaw.class)
    public ResponseEntity<ApiErrorResponse> handleRefreshTokenDontMatchRaw(RefreshTokenDontMatchRaw ex){
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.REFRESH_TOKEN_INVALID.name(), ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException ex){
        ApiErrorResponse response = new ApiErrorResponse(ex.getErrorKey().name(), ex.getMessage(), null);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex){
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.INVALID_REQUEST.name(), ex.getMessage(), null);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex){
        Map<String, String> errors = new HashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }

        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.INVALID_REQUEST.name(), "Validation failed", errors);
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpClientErrorException(HttpClientErrorException ex){
        log.warn("Upstream HTTP request failed: {} {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.EXTERNAL_SERVICE_ERROR.name(),
                "An upstream service request failed, try again later", null);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    /**
     * Container-level multipart limit exceeded — larger than either service's
     * own 5MB check can report, because the request never reaches a controller.
     *
     * That is also why the path is the only thing left to go on. Two endpoints
     * accept uploads and each publishes its own error key, which the clients
     * match on for i18n: a feedback screenshot rejected as
     * {@code PHOTO_UPLOAD_TOO_LARGE} tells the user their "photo" was too big
     * when they were attaching a screenshot, and no client string exists for
     * the case they are actually in.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpServletRequest request){
        ApiErrorResponse response = isFeedbackAttachmentUpload(request)
                ? new ApiErrorResponse(ErrorKey.FEEDBACK_ATTACHMENT_TOO_LARGE.name(),
                        "Attachment must be under 5MB", null)
                : new ApiErrorResponse(ErrorKey.PHOTO_UPLOAD_TOO_LARGE.name(),
                        "Photo must be under 5MB", null);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    /**
     * {@code POST /feedback/{feedbackId}/attachments}, with the servlet
     * context-path (e.g. {@code /api/v1}) stripped the same way
     * {@code RateLimitFilter} strips it, so the comparison survives versioning.
     * The profile-photo route and everything else fall through to the photo key.
     */
    private static boolean isFeedbackAttachmentUpload(HttpServletRequest request){
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return path.startsWith("/feedback/") && path.endsWith("/attachments");
    }

    /**
     * Bean Validation on a handler method's OWN parameters — the
     * {@code @Min}/{@code @Max} on the admin listing's {@code page} and
     * {@code size}, for instance.
     *
     * This is a different path from {@code @Valid} on a request body, and it
     * surfaces as one of two exceptions depending on how the constraint is
     * evaluated: Spring's AOP method validation (a controller carrying
     * {@code @Validated}) raises {@link ConstraintViolationException}, while
     * Spring MVC's own built-in method validation raises
     * {@link HandlerMethodValidationException}. Neither is a
     * {@code MethodArgumentNotValidException}, so without these handlers a
     * hand-edited query string answers 500 with no {@code errorKey} at all —
     * outside the envelope every client parses.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolationException(ConstraintViolationException ex){
        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.put(lastPathNode(violation.getPropertyPath()), violation.getMessage());
        }

        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.INVALID_REQUEST.name(),
                "Validation failed", errors.isEmpty() ? null : errors);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex){
        Map<String, String> errors = new HashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    errors.put(name != null ? name : "request", error.getDefaultMessage()));
        }

        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.INVALID_REQUEST.name(),
                "Validation failed", errors.isEmpty() ? null : errors);
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * A method-validation property path reads {@code listSubmissions.size};
     * clients care about {@code size}.
     */
    private static String lastPathNode(Path propertyPath) {
        String rendered = propertyPath.toString();
        int lastDot = rendered.lastIndexOf('.');
        return lastDot >= 0 && lastDot < rendered.length() - 1 ? rendered.substring(lastDot + 1) : rendered;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolationException(DataIntegrityViolationException ex){
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.DUPLICATE_CHECK.name(), "Duplicate check: this item has already been checked for the given date", null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

}
