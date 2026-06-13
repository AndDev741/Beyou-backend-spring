package beyou.beyouapp.backend.controllers;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import beyou.beyouapp.backend.domain.ai.AiRoutineConfirmService;
import beyou.beyouapp.backend.domain.ai.AiRoutineService;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineRequestDTO;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineResponseDTO;
import beyou.beyouapp.backend.domain.ai.dto.MaterializeRoutineResponseDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/ai/routine")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.routine.enabled", havingValue = "true", matchIfMissing = true)
public class AiRoutineController {

    private final AiRoutineService aiRoutineService;
    private final AiRoutineConfirmService aiRoutineConfirmService;
    private final AuthenticatedUser authenticatedUser;

    /** Stateless: returns a draft for preview/edit. Persists nothing. */
    @PostMapping("/generate")
    public ResponseEntity<GenerateRoutineResponseDTO> generate(
            @Valid @RequestBody GenerateRoutineRequestDTO request) {
        User user = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok(aiRoutineService.generate(request, user));
    }

    /**
     * Persists the draft's NEW categories/habits/tasks and returns the structure
     * as plain refs, shaped for the manual routine form. The routine itself is
     * not created — the user finishes through the standard create/edit flow.
     */
    @PostMapping("/materialize")
    public ResponseEntity<MaterializeRoutineResponseDTO> materialize(@Valid @RequestBody RoutineDraftDTO draft) {
        User user = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok(aiRoutineConfirmService.materialize(draft, user));
    }

    /**
     * Transactional: creates categories → habits → tasks → routine atomically.
     * With ?routineId=… the draft REPLACES the structure of that existing routine
     * (AI-assisted edit) instead of creating a new one.
     */
    @PostMapping("/confirm")
    public ResponseEntity<DiaryRoutineResponseDTO> confirm(
            @Valid @RequestBody RoutineDraftDTO draft,
            @RequestParam(name = "routineId", required = false) UUID routineId) {
        User user = authenticatedUser.getAuthenticatedUser();
        DiaryRoutineResponseDTO response = aiRoutineConfirmService.confirm(draft, user, routineId);
        if (routineId != null) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/diary-routines/" + response.id())).body(response);
    }
}
