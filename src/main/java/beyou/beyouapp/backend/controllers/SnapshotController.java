package beyou.beyouapp.backend.controllers;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckService;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotCheckRequestDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotMonthResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequestMapping("/routine")
@RequiredArgsConstructor
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final SnapshotCheckService snapshotCheckService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping("/{routineId}/snapshot")
    public ResponseEntity<SnapshotResponseDTO> getSnapshot(
            @PathVariable UUID routineId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        User user = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok(snapshotService.getSnapshot(routineId, date, user.getId()));
    }

    @GetMapping("/{routineId}/snapshots")
    public ResponseEntity<SnapshotMonthResponseDTO> getSnapshotDatesForMonth(
            @PathVariable UUID routineId,
            @RequestParam String month) {
        User user = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok(snapshotService.getSnapshotDatesForMonth(routineId, month, user.getId()));
    }

    @PostMapping("/snapshot/check")
    public ResponseEntity<RefreshUiDTO> checkSnapshotItem(
            @Valid @RequestBody SnapshotCheckRequestDTO request) {
        log.info("Initializing snapshot check request");
        return ResponseEntity.ok(snapshotCheckService.checkOrUncheckSnapshotItem(
                request.snapshotId(), request.snapshotCheckId()));
    }

    @PostMapping("/snapshot/skip")
    public ResponseEntity<RefreshUiDTO> skipSnapshotItem(
            @Valid @RequestBody SnapshotCheckRequestDTO request) {
        log.info("Initializing snapshot skip request");
        return ResponseEntity.ok(snapshotCheckService.skipOrUnskipSnapshotItem(
                request.snapshotId(), request.snapshotCheckId()));
    }
}
