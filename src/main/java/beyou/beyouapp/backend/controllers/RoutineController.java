package beyou.beyouapp.backend.controllers;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
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
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequestMapping("/routine")
@RequiredArgsConstructor
public class RoutineController {

    @Autowired
    private final DiaryRoutineService diaryRoutineService;

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @PostMapping
    public ResponseEntity<DiaryRoutineResponseDTO> createDiaryRoutine(@Valid @RequestBody DiaryRoutineRequestDTO dto) {
        User userAuth = authenticatedUser.getAuthenticatedUser();
        DiaryRoutineResponseDTO response = diaryRoutineService.createDiaryRoutine(dto, userAuth);
        return ResponseEntity.created(URI.create("/api/diary-routines/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DiaryRoutineResponseDTO> getDiaryRoutineById(@PathVariable UUID id) {
        User userAuth = authenticatedUser.getAuthenticatedUser();
        DiaryRoutineResponseDTO response = diaryRoutineService.getDiaryRoutineById(id, userAuth.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<DiaryRoutineResponseDTO>> getAllDiaryRoutines() {
        User userAuth = authenticatedUser.getAuthenticatedUser();
        List<DiaryRoutineResponseDTO> response = diaryRoutineService.getAllDiaryRoutines(userAuth.getId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DiaryRoutineResponseDTO> updateDiaryRoutine(@PathVariable UUID id,
            @Valid @RequestBody DiaryRoutineRequestDTO dto) {
        User userAuth = authenticatedUser.getAuthenticatedUser();
        DiaryRoutineResponseDTO response = diaryRoutineService.updateDiaryRoutine(id, dto, userAuth.getId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDiaryRoutine(@PathVariable UUID id) {
        User userAuth = authenticatedUser.getAuthenticatedUser();
        diaryRoutineService.deleteDiaryRoutine(id, userAuth.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/today")
    public ResponseEntity<DiaryRoutineResponseDTO> getTodayRoutineScheduled(){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok().body(diaryRoutineService.getTodayRoutineScheduled(userAuth.getId()));
    }

    @PostMapping("/check")
    public ResponseEntity<DiaryRoutineResponseDTO> checkItem(@RequestBody CheckGroupRequestDTO checkGroupRequestDTO){
        log.info("Initializing check request");
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok().body(diaryRoutineService.checkGroup(checkGroupRequestDTO, userAuth.getId()));
    }
}
