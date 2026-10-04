package beyou.beyouapp.backend.controllers;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.domain.notebook.study.dto.QuizAnswersRequestDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.QuizResultDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyOutputDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Study notebook: the study room's reads and the writes that call no model. Asking a question and
 * generating an output are model calls and live in {@link NotebookAiController}.
 */
@RestController
@RequestMapping("/notebook")
@RequiredArgsConstructor
public class NotebookStudyController {

    private final NotebookStudyService studyService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping("/pages/{pageId}/study")
    public ResponseEntity<StudyResponseDTO> study(@PathVariable UUID pageId) {
        return ResponseEntity.ok(studyService.study(authenticatedUser.getAuthenticatedUser(), pageId));
    }

    @DeleteMapping("/pages/{pageId}/study/messages")
    public ResponseEntity<Void> clearChat(@PathVariable UUID pageId) {
        studyService.clearChat(authenticatedUser.getAuthenticatedUser(), pageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/outputs/{outputId}")
    public ResponseEntity<StudyOutputDTO> output(@PathVariable UUID outputId) {
        return ResponseEntity.ok(studyService.output(authenticatedUser.getAuthenticatedUser(), outputId));
    }

    /** Grades a quiz; the first pass pays XP. */
    @PostMapping("/outputs/{outputId}/quiz-result")
    public ResponseEntity<QuizResultDTO> grade(@PathVariable UUID outputId,
            @Valid @RequestBody QuizAnswersRequestDTO request) {
        return ResponseEntity.ok(studyService.grade(authenticatedUser.getAuthenticatedUser(), outputId, request.answers()));
    }

    @DeleteMapping("/outputs/{outputId}")
    public ResponseEntity<Void> deleteOutput(@PathVariable UUID outputId) {
        studyService.deleteOutput(authenticatedUser.getAuthenticatedUser(), outputId);
        return ResponseEntity.noContent().build();
    }
}
