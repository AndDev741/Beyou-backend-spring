package beyou.beyouapp.backend.controllers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.CreateMicroTaskRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusCycleResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusDayResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.RecordCycleRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The Focus Mode's history. Every route is authenticated by the default rule, and the rate limiter
 * files writes under the {@code write:} tier and reads under {@code read:} without configuration.
 */
@RestController
@RequestMapping("/focus")
@RequiredArgsConstructor
public class FocusController {

    private final FocusService focusService;
    private final AuthenticatedUser authenticatedUser;

    @PostMapping("/cycles")
    public ResponseEntity<FocusCycleResponseDTO> recordCycle(@Valid @RequestBody RecordCycleRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(focusService.recordCycle(authenticatedUser.getAuthenticatedUser(), request));
    }

    @GetMapping("/day")
    public ResponseEntity<FocusDayResponseDTO> getDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(focusService.getDay(authenticatedUser.getAuthenticatedUser(), date));
    }

    /** Today's list for one item. Materialises pinned templates on the way, see {@code FocusService}. */
    @GetMapping("/micro-tasks")
    public ResponseEntity<List<FocusMicroTaskResponseDTO>> listMicroTasks(@RequestParam UUID itemGroupId) {
        return ResponseEntity.ok(focusService.listMicroTasks(authenticatedUser.getAuthenticatedUser(), itemGroupId));
    }

    @PostMapping("/micro-tasks")
    public ResponseEntity<FocusMicroTaskResponseDTO> addMicroTask(@Valid @RequestBody CreateMicroTaskRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(focusService.addMicroTask(authenticatedUser.getAuthenticatedUser(), request));
    }

    @PatchMapping("/micro-tasks/{id}/toggle")
    public ResponseEntity<FocusMicroTaskResponseDTO> toggle(@PathVariable UUID id) {
        return ResponseEntity.ok(focusService.toggleMicroTask(authenticatedUser.getAuthenticatedUser(), id));
    }

    @PatchMapping("/micro-tasks/{id}/pin")
    public ResponseEntity<FocusMicroTaskResponseDTO> pin(@PathVariable UUID id, @RequestParam boolean pinned) {
        return ResponseEntity.ok(focusService.setPinned(authenticatedUser.getAuthenticatedUser(), id, pinned));
    }

    @DeleteMapping("/micro-tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        focusService.deleteMicroTask(authenticatedUser.getAuthenticatedUser(), id);
        return ResponseEntity.noContent().build();
    }
}
