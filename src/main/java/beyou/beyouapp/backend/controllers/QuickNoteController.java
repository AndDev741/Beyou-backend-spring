package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.quicknote.QuickNoteService;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteCreateRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteEditRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteLayoutRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteResponseDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteTaskConversionDTO;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The Ideas board: quick notes, where they sit, the archive, and "make it a task".
 *
 * <p>A note the caller does not own answers 404 {@code QUICK_NOTE_NOT_FOUND} on every route, the
 * same as one that does not exist. A full board answers 409 {@code QUICK_NOTE_LIMIT_REACHED} on
 * create and restore, and a second conversion 409 {@code QUICK_NOTE_ALREADY_CONVERTED}.
 *
 * <p>Every route is authenticated by the default rule, and the rate limiter files the writes
 * under the {@code write:} tier and the reads under {@code read:} with no configuration.
 */
@RestController
@RequestMapping("/quick-notes")
@RequiredArgsConstructor
public class QuickNoteController {

    private final QuickNoteService quickNoteService;
    private final AuthenticatedUser authenticatedUser;

    /** The board: active notes, z ascending, then oldest first. */
    @GetMapping
    public ResponseEntity<List<QuickNoteResponseDTO>> listActive() {
        return ResponseEntity.ok(quickNoteService.listActive(authenticatedUser.getAuthenticatedUser()));
    }

    /** The archive, most recently archived first, at most 100. */
    @GetMapping("/archived")
    public ResponseEntity<List<QuickNoteResponseDTO>> listArchived() {
        return ResponseEntity.ok(quickNoteService.listArchived(authenticatedUser.getAuthenticatedUser()));
    }

    /** A new note, in front. Without both x and y it takes the first free grid cell. */
    @PostMapping
    public ResponseEntity<QuickNoteResponseDTO> create(@Valid @RequestBody QuickNoteCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(quickNoteService.create(authenticatedUser.getAuthenticatedUser(), request));
    }

    /** Positions for one or more active notes, all saved or none. Answers the whole board. */
    @PutMapping("/layout")
    public ResponseEntity<List<QuickNoteResponseDTO>> saveLayout(@Valid @RequestBody QuickNoteLayoutRequestDTO request) {
        return ResponseEntity.ok(quickNoteService.saveLayout(authenticatedUser.getAuthenticatedUser(), request));
    }

    /** "Tidy up": every active note into the grid, grouped by colour. Answers the whole board. */
    @PostMapping("/tidy")
    public ResponseEntity<List<QuickNoteResponseDTO>> tidy() {
        return ResponseEntity.ok(quickNoteService.tidy(authenticatedUser.getAuthenticatedUser()));
    }

    /** New text and colour. */
    @PutMapping("/{id}")
    public ResponseEntity<QuickNoteResponseDTO> edit(
            @PathVariable UUID id, @Valid @RequestBody QuickNoteEditRequestDTO request) {
        return ResponseEntity.ok(quickNoteService.edit(authenticatedUser.getAuthenticatedUser(), id, request));
    }

    /** Off the board, into the archive. Idempotent. */
    @PostMapping("/{id}/archive")
    public ResponseEntity<QuickNoteResponseDTO> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(quickNoteService.archive(authenticatedUser.getAuthenticatedUser(), id));
    }

    /** Back on the board where it was, in front. Idempotent; the limit applies. */
    @PostMapping("/{id}/restore")
    public ResponseEntity<QuickNoteResponseDTO> restore(@PathVariable UUID id) {
        return ResponseEntity.ok(quickNoteService.restore(authenticatedUser.getAuthenticatedUser(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        quickNoteService.delete(authenticatedUser.getAuthenticatedUser(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates the task from the body (the same shape as {@code POST /task}) and archives the note
     * pointing at it, in one transaction.
     */
    @PostMapping("/{id}/task")
    public ResponseEntity<QuickNoteTaskConversionDTO> convertToTask(
            @PathVariable UUID id, @Valid @RequestBody CreateTaskRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(quickNoteService.convertToTask(authenticatedUser.getAuthenticatedUser(), id, request));
    }
}
