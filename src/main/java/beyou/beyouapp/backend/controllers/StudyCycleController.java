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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleHistoryService;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleService;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockPatchDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleConfigRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.PlanRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.PlanResponseDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionResponseDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyHistoryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyTodayDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Study notebook: study cycles and manual study sessions.
 *
 * <p>Thin on purpose: every rule is in {@link StudyCycleService}, which the focus timer, the page
 * status changes and the assistant reach too. {@code /cycles/today} and {@code /cycles/plan} are
 * literal paths, so Spring matches them before {@code /cycles/{id}}. Writes fall under the
 * rate limiter's {@code write:} tier and reads under {@code read:}, like the rest of
 * {@code /notebook}.
 */
@RestController
@RequestMapping("/notebook")
@RequiredArgsConstructor
public class StudyCycleController {

    private final StudyCycleService cycleService;
    private final StudyCycleHistoryService historyService;
    private final AuthenticatedUser authenticatedUser;

    /** The active cycles, in display order. */
    @GetMapping("/cycles")
    public ResponseEntity<List<StudyCycleSummaryDTO>> list() {
        return ResponseEntity.ok(cycleService.list(authenticatedUser.getAuthenticatedUser()));
    }

    /** The editor's preview: the planner's blocks for a week and topics. Writes nothing. */
    @PostMapping("/cycles/plan")
    public ResponseEntity<PlanResponseDTO> plan(@Valid @RequestBody PlanRequestDTO request) {
        return ResponseEntity.ok(cycleService.plan(request));
    }

    @PostMapping("/cycles")
    public ResponseEntity<StudyCycleDTO> create(@Valid @RequestBody CycleConfigRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cycleService.create(authenticatedUser.getAuthenticatedUser(), request));
    }

    /** Today's slice of every active cycle, with clock times where the day has a start time. */
    @GetMapping("/cycles/today")
    public ResponseEntity<StudyTodayDTO> today() {
        return ResponseEntity.ok(cycleService.today(authenticatedUser.getAuthenticatedUser()));
    }

    @GetMapping("/cycles/{cycleId}")
    public ResponseEntity<StudyCycleDTO> get(@PathVariable UUID cycleId) {
        return ResponseEntity.ok(cycleService.get(authenticatedUser.getAuthenticatedUser(), cycleId));
    }

    /** The configuration; the blocks are planned again and merged when the plan's inputs moved. */
    @PutMapping("/cycles/{cycleId}")
    public ResponseEntity<StudyCycleDTO> update(@PathVariable UUID cycleId,
            @Valid @RequestBody CycleConfigRequestDTO request) {
        return ResponseEntity.ok(cycleService.update(authenticatedUser.getAuthenticatedUser(), cycleId, request));
    }

    /** The whole queue, in order: existing blocks by id, new ones without. */
    @PutMapping("/cycles/{cycleId}/blocks")
    public ResponseEntity<StudyCycleDTO> replaceBlocks(@PathVariable UUID cycleId,
            @Valid @RequestBody List<@Valid BlockRequestDTO> request) {
        return ResponseEntity.ok(cycleService.replaceBlocks(authenticatedUser.getAuthenticatedUser(), cycleId, request));
    }

    @PatchMapping("/cycles/{cycleId}/blocks/{blockId}")
    public ResponseEntity<StudyCycleDTO> patchBlock(@PathVariable UUID cycleId, @PathVariable UUID blockId,
            @Valid @RequestBody BlockPatchDTO request) {
        return ResponseEntity.ok(cycleService.patchBlock(authenticatedUser.getAuthenticatedUser(), cycleId, blockId, request));
    }

    /** Puts the cycle away and frees its topics. */
    @PostMapping("/cycles/{cycleId}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID cycleId) {
        cycleService.archive(authenticatedUser.getAuthenticatedUser(), cycleId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/cycles/{cycleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID cycleId) {
        cycleService.delete(authenticatedUser.getAuthenticatedUser(), cycleId);
        return ResponseEntity.noContent().build();
    }

    /** At most 31 days, both ends included. */
    @GetMapping("/cycles/{cycleId}/history")
    public ResponseEntity<StudyHistoryDTO> history(@PathVariable UUID cycleId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(historyService.history(authenticatedUser.getAuthenticatedUser(), cycleId, from, to));
    }

    /** Study done outside the app. Credited into the current lap at once. */
    @PostMapping("/sessions")
    public ResponseEntity<SessionResponseDTO> logSession(@Valid @RequestBody SessionRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cycleService.logSession(authenticatedUser.getAuthenticatedUser(), request));
    }

    /** Takes the session's minutes back out of the lap it went into, if that lap is still running. */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID sessionId) {
        cycleService.deleteSession(authenticatedUser.getAuthenticatedUser(), sessionId);
        return ResponseEntity.noContent().build();
    }
}
