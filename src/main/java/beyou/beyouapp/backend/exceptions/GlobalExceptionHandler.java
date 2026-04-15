package beyou.beyouapp.backend.exceptions;

import beyou.beyouapp.backend.exceptions.security.JwtNotFoundException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
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
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.GOOGLE_OAUTH_FAILED.name(), "Error trying login with Google, try again", null);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolationException(DataIntegrityViolationException ex){
        ApiErrorResponse response = new ApiErrorResponse(ErrorKey.DUPLICATE_CHECK.name(), "Duplicate check: this item has already been checked for the given date", null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

}
