package beyou.beyouapp.backend.controllers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.calendar.CalendarService;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarOverviewDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskCompletionDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskRequestDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The calendar: a range overview and the dated tasks inside it.
 *
 * <p>A task the caller does not own answers 404 {@code CALENDAR_TASK_NOT_FOUND} on every route,
 * the same as one that does not exist, so no route tells a caller which ids are taken.
 *
 * <p>Every route is authenticated by the default rule, and the rate limiter files the writes
 * under the {@code write:} tier and the reads under {@code read:} with no configuration.
 */
@RestController
@RequestMapping("/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;
    private final AuthenticatedUser authenticatedUser;

    /**
     * One entry per day in {@code [from, to]}: its tasks, routines, goals ending that day, and
     * mood. Both bounds are required; a missing one, {@code to} before {@code from}, or more than
     * 62 days answers 400 {@code CALENDAR_RANGE_INVALID}.
     */
    @GetMapping
    public ResponseEntity<CalendarOverviewDTO> getOverview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(
                calendarService.getOverview(authenticatedUser.getAuthenticatedUser(), from, to));
    }

    @PostMapping("/tasks")
    public ResponseEntity<CalendarTaskResponseDTO> create(@Valid @RequestBody CalendarTaskRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(calendarService.create(authenticatedUser.getAuthenticatedUser(), request));
    }

    /** Replaces the task. Moving its date, time or advance re-arms the reminder. */
    @PutMapping("/tasks/{id}")
    public ResponseEntity<CalendarTaskResponseDTO> update(
            @PathVariable UUID id, @Valid @RequestBody CalendarTaskRequestDTO request) {
        return ResponseEntity.ok(
                calendarService.update(authenticatedUser.getAuthenticatedUser(), id, request));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        calendarService.delete(authenticatedUser.getAuthenticatedUser(), id);
        return ResponseEntity.noContent().build();
    }

    /** Toggles completion and moves the XP with it. No body. */
    @PutMapping("/tasks/{id}/complete")
    public ResponseEntity<CalendarTaskCompletionDTO> toggleComplete(@PathVariable UUID id) {
        return ResponseEntity.ok(
                calendarService.toggleComplete(authenticatedUser.getAuthenticatedUser(), id));
    }

    /** The browser notification was clicked. Idempotent; stops the reminder mail. */
    @PostMapping("/tasks/{id}/ack")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID id) {
        calendarService.acknowledge(authenticatedUser.getAuthenticatedUser(), id);
        return ResponseEntity.noContent().build();
    }

    /** Open, unacknowledged reminders due from an hour ago to a day ahead, soonest first. */
    @GetMapping("/tasks/upcoming")
    public ResponseEntity<List<CalendarTaskResponseDTO>> upcoming() {
        return ResponseEntity.ok(calendarService.upcoming(authenticatedUser.getAuthenticatedUser()));
    }
}
