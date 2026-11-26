package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.security.aiconsent.SendsToAiProvider;
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

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainService;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSource;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainInsightDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainNoteDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainNoteRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOldNoteRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOldNoteResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOverviewDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainSourceDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainSourceToggleRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Second Brain: what Beyou understands about the caller's life, the interview that fills it
 * in, the profile every chat should know, the notes it keeps about the caller's things, the
 * assistant's old hidden note under review, and the switches for what it may read.
 *
 * <p>A fact, a question or a note the caller does not own answers 404
 * ({@code SECOND_BRAIN_FACT_NOT_FOUND}, {@code SECOND_BRAIN_QUESTION_NOT_FOUND},
 * {@code SECOND_BRAIN_NOTE_NOT_FOUND}), the same as one that does not exist. A sentence the old
 * note does not hold answers 404 {@code SECOND_BRAIN_OLD_NOTE_LINE_NOT_FOUND}. A full profile answers 409 {@code SECOND_BRAIN_FACT_LIMIT_REACHED},
 * and a question already answered or skipped 409 {@code SECOND_BRAIN_QUESTION_CLOSED}.
 *
 * <p>Every route is authenticated by the default rule. The three that can call a model (the
 * refill, an answer, the overview refresh) spend the hourly {@code second-brain:} bucket in
 * {@code RateLimitFilter}; the rest, the notes and the old note included, fall under the
 * generic read and write tiers.
 */
@RestController
@RequestMapping("/second-brain")
@RequiredArgsConstructor
public class SecondBrainController {

    private final SecondBrainService secondBrainService;
    private final AuthenticatedUser authenticatedUser;

    /** The whole page. Writes nothing. */
    @GetMapping
    public ResponseEntity<SecondBrainOverviewDTO> overview() {
        return ResponseEntity.ok(secondBrainService.overview(authenticatedUser.getAuthenticatedUser()));
    }

    /** Turns one source on or off. Answers all six switches. An unknown source is a 400. */
    @PutMapping("/sources/{source}")
    public ResponseEntity<List<SecondBrainSourceDTO>> setSource(
            @PathVariable String source, @Valid @RequestBody SecondBrainSourceToggleRequestDTO request) {
        return ResponseEntity.ok(secondBrainService.setSource(
                authenticatedUser.getAuthenticatedUser(), SecondBrainSource.fromPath(source), request.enabled()));
    }

    @PostMapping("/facts")
    public ResponseEntity<SecondBrainFactDTO> createFact(@Valid @RequestBody SecondBrainFactRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(secondBrainService.createFact(authenticatedUser.getAuthenticatedUser(), request));
    }

    @PutMapping("/facts/{id}")
    public ResponseEntity<SecondBrainFactDTO> editFact(@PathVariable UUID id, @Valid @RequestBody SecondBrainFactRequestDTO request) {
        return ResponseEntity.ok(secondBrainService.editFact(authenticatedUser.getAuthenticatedUser(), id, request));
    }

    @DeleteMapping("/facts/{id}")
    public ResponseEntity<Void> deleteFact(@PathVariable UUID id) {
        secondBrainService.deleteFact(authenticatedUser.getAuthenticatedUser(), id);
        return ResponseEntity.noContent().build();
    }

    /** Corrects a note's words. The origin stays what it was. */
    @PutMapping("/notes/{id}")
    public ResponseEntity<SecondBrainNoteDTO> editNote(@PathVariable UUID id, @Valid @RequestBody SecondBrainNoteRequestDTO request) {
        return ResponseEntity.ok(secondBrainService.editNote(authenticatedUser.getAuthenticatedUser(), id, request));
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable UUID id) {
        secondBrainService.deleteNote(authenticatedUser.getAuthenticatedUser(), id);
        return ResponseEntity.noContent().build();
    }

    /** Keeps one sentence of the old note as a profile line. 409 with the profile full. */
    @PostMapping("/old-note/keep")
    public ResponseEntity<SecondBrainOldNoteResultDTO> keepOldNoteLine(@Valid @RequestBody SecondBrainOldNoteRequestDTO request) {
        return ResponseEntity.ok(secondBrainService.keepOldNoteLine(authenticatedUser.getAuthenticatedUser(), request));
    }

    @PostMapping("/old-note/discard")
    public ResponseEntity<SecondBrainOldNoteResultDTO> discardOldNoteLine(@Valid @RequestBody SecondBrainOldNoteRequestDTO request) {
        return ResponseEntity.ok(secondBrainService.discardOldNoteLine(authenticatedUser.getAuthenticatedUser(), request));
    }

    /** Deletes what is left of the old note. */
    @DeleteMapping("/old-note")
    public ResponseEntity<Void> deleteOldNote() {
        secondBrainService.deleteOldNote(authenticatedUser.getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    /** Tops the interview up (seeds, the daily check-in, follow-ups) and answers the OPEN questions. */
    @SendsToAiProvider
    @PostMapping("/questions/refill")
    public ResponseEntity<List<SecondBrainQuestionDTO>> refill() {
        return ResponseEntity.ok(secondBrainService.refill(authenticatedUser.getAuthenticatedUser()));
    }

    /** Closes the question with the answer and says which facts and notes it left. */
    @SendsToAiProvider
    @PostMapping("/questions/{id}/answer")
    public ResponseEntity<SecondBrainAnswerResultDTO> answer(
            @PathVariable UUID id, @Valid @RequestBody SecondBrainAnswerRequestDTO request) {
        return ResponseEntity.ok(secondBrainService.answer(authenticatedUser.getAuthenticatedUser(), id, request));
    }

    @PostMapping("/questions/{id}/skip")
    public ResponseEntity<SecondBrainQuestionDTO> skip(@PathVariable UUID id) {
        return ResponseEntity.ok(secondBrainService.skip(authenticatedUser.getAuthenticatedUser(), id));
    }

    /** Writes the overview again. {@code AI_UNAVAILABLE} when no model answers. */
    @SendsToAiProvider
    @PostMapping("/insight/refresh")
    public ResponseEntity<SecondBrainInsightDTO> refreshInsight() {
        return ResponseEntity.ok(secondBrainService.refreshInsight(authenticatedUser.getAuthenticatedUser()));
    }
}
