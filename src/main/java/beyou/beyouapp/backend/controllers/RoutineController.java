package beyou.beyouapp.backend.controllers;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/routine")
@RequiredArgsConstructor
public class RoutineController {
    private final DiaryRoutineService diaryRoutineService;

    @PostMapping
    public ResponseEntity<DiaryRoutineResponseDTO> createDiaryRoutine(@Valid @RequestBody DiaryRoutineRequestDTO dto) {
        DiaryRoutineResponseDTO response = diaryRoutineService.createDiaryRoutine(dto);
        return ResponseEntity.created(URI.create("/api/diary-routines/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DiaryRoutineResponseDTO> getDiaryRoutineById(@PathVariable UUID id) {
        DiaryRoutineResponseDTO response = diaryRoutineService.getDiaryRoutineById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<DiaryRoutineResponseDTO>> getAllDiaryRoutines() {
        List<DiaryRoutineResponseDTO> response = diaryRoutineService.getAllDiaryRoutines();
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DiaryRoutineResponseDTO> updateDiaryRoutine(@PathVariable UUID id, @Valid @RequestBody DiaryRoutineRequestDTO dto) {
        DiaryRoutineResponseDTO response = diaryRoutineService.updateDiaryRoutine(id, dto);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDiaryRoutine(@PathVariable UUID id) {
        diaryRoutineService.deleteDiaryRoutine(id);
        return ResponseEntity.noContent().build();
    }
}
