package beyou.beyouapp.backend.exceptions;

import beyou.beyouapp.backend.exceptions.category.CategoryNotFound;
import beyou.beyouapp.backend.exceptions.habit.HabitNotFound;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import beyou.beyouapp.backend.exceptions.task.TaskNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
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

    @ExceptionHandler(JwtCookieNotFoundException.class)
    public ResponseEntity<String> handleJwtCookieNotFoundException(JwtCookieNotFoundException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex){
        return ResponseEntity.badRequest().body(Map.of("argumentError", "Error in a passed parameter"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex){
        Map<String, String> errors = new HashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }

        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, String>> handleHttpClientErrorException(HttpClientErrorException ex){
        return ResponseEntity.badRequest().body(Map.of("error", "error trying login with google, try again"));
    }

    @ExceptionHandler(UserNotFound.class)
    public ResponseEntity<Map<String, String>> handleUserNotFoundException(UserNotFound ex){
        return ResponseEntity.badRequest().body(Map.of("error", "User Not Found"));
    }

    @ExceptionHandler(CategoryNotFound.class)
    public ResponseEntity<Map<String, String>> handleCategoryNotFoundException(CategoryNotFound ex){
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(HabitNotFound.class)
    public ResponseEntity<Map<String, String>> handleHabitNotFoundException(HabitNotFound ex){
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(TaskNotFound.class)
    public ResponseEntity<Map<String, String>> handleTaskNotFoundException(TaskNotFound ex){
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DiaryRoutineNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRoutineNotFoundException(DiaryRoutineNotFoundException ex){
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

}
