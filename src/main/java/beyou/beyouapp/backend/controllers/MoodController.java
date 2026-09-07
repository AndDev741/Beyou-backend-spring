package beyou.beyouapp.backend.controllers;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.mood.MoodService;
import beyou.beyouapp.backend.domain.mood.dto.MoodEntryResponseDTO;
import beyou.beyouapp.backend.domain.mood.dto.SetMoodLevelDTO;
import beyou.beyouapp.backend.domain.mood.dto.UpsertMoodEntryDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Daily mood and journaling.
 *
 * <p>The day is the resource: {@code /mood/2026-09-06} addresses one entry, and writing to it
 * twice leaves one row. Two write verbs, and the difference between them matters —
 * {@code PUT} replaces the entry including the note, {@code PATCH} sets only the level and
 * cannot touch the note. The dashboard widget uses {@code PATCH}, so a client that has never
 * loaded the day's writing has no way to erase it.
 *
 * <p>There is no single-day GET. A day with no entry is an ordinary state, not an error, and a
 * route that had to answer 404 for it would make every client catch an exception for the common
 * case. {@code GET /mood?from=X&to=X} asks the same question and answers with an empty list.
 *
 * <p>Every route is authenticated by the default rule, and the rate limiter files the writes
 * under the {@code write:} tier and the read under {@code read:} with no configuration.
 */
@RestController
@RequestMapping("/mood")
@RequiredArgsConstructor
public class MoodController {

    private final MoodService moodService;
    private final AuthenticatedUser authenticatedUser;

    /**
     * The entries in a window of days, newest first.
     *
     * @param from first day inclusive; omit for {@code to} minus six days
     * @param to   last day inclusive; omit for the caller's today, in their own zone
     */
    @GetMapping
    public ResponseEntity<List<MoodEntryResponseDTO>> getRange(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(
                moodService.getRange(authenticatedUser.getAuthenticatedUser(), from, to));
    }

    /**
     * Replaces the day's entry, note included. A body with no note clears the note — this is
     * the page's Save button, where the textarea on screen is the day's final text.
     */
    @PutMapping("/{date}")
    public ResponseEntity<MoodEntryResponseDTO> upsert(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody UpsertMoodEntryDTO request) {
        return ResponseEntity.ok(
                moodService.upsert(authenticatedUser.getAuthenticatedUser(), date, request));
    }

    /**
     * Sets the day's level and leaves any note untouched. Creates the entry when the day has
     * none, so one tap on the widget is a complete interaction.
     */
    @PatchMapping("/{date}")
    public ResponseEntity<MoodEntryResponseDTO> setLevel(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody SetMoodLevelDTO request) {
        return ResponseEntity.ok(
                moodService.setLevel(authenticatedUser.getAuthenticatedUser(), date, request));
    }

    @DeleteMapping("/{date}")
    public ResponseEntity<Void> delete(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        moodService.delete(authenticatedUser.getAuthenticatedUser(), date);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
