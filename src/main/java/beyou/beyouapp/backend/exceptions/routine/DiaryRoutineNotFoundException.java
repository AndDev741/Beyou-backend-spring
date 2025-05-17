package beyou.beyouapp.backend.exceptions.routine;

import java.util.UUID;

public class DiaryRoutineNotFoundException extends RuntimeException {
    public DiaryRoutineNotFoundException(UUID id) {
        super("DiaryRoutine not found with id: " + id);
    }
} 

