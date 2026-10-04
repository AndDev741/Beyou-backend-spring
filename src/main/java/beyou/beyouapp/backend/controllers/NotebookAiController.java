package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AiCardsRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AnswerDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.ChatRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CreateFromDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.ExplainRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.GenerateOutputRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.SuggestNodesRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.SuggestedNodeDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.CardDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.domain.notebook.study.dto.ChatTurnDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyOutputDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Study notebook: every route that calls a model, under {@code /notebook/ai}, which is how the rate
 * limiter files them all under the {@code notebook-ai:} tier with one prefix check. Each answers
 * {@code AI_UNAVAILABLE} when the fallback chain is exhausted, and {@code NOTEBOOK_NOTHING_TO_STUDY}
 * when a grounded call has no notes and no sources to stand on.
 */
@RestController
@RequestMapping("/notebook")
@RequiredArgsConstructor
public class NotebookAiController {

    private final NotebookAiService aiService;
    private final NotebookStudyService studyService;
    private final AuthenticatedUser authenticatedUser;

    /** "New topic with AI": a draft to review. Nothing is stored. */
    @PostMapping("/ai/roadmap-draft")
    public ResponseEntity<RoadmapDraftDTO> roadmapDraft(@Valid @RequestBody RoadmapDraftRequestDTO request) {
        return ResponseEntity.ok(aiService.roadmapDraft(authenticatedUser.getAuthenticatedUser(), request));
    }

    /**
     * Creates the reviewed draft. No model is called, so this one is NOT under {@code /ai}: it is
     * an ordinary write and spends the ordinary write budget.
     */
    @PostMapping("/topics/from-draft")
    public ResponseEntity<PageResponseDTO> createFromDraft(@Valid @RequestBody CreateFromDraftRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(aiService.createFromDraft(authenticatedUser.getAuthenticatedUser(), request));
    }

    @PostMapping("/ai/pages/{pageId}/suggest-nodes")
    public ResponseEntity<List<SuggestedNodeDTO>> suggestNodes(@PathVariable UUID pageId,
            @RequestBody SuggestNodesRequestDTO request) {
        return ResponseEntity.ok(aiService.suggestNodes(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    @PostMapping("/ai/pages/{pageId}/explain")
    public ResponseEntity<AnswerDTO> explain(@PathVariable UUID pageId, @Valid @RequestBody ExplainRequestDTO request) {
        return ResponseEntity.ok(aiService.explain(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    /** Flashcards drafted by the AI and saved to the page. */
    @PostMapping("/ai/pages/{pageId}/cards")
    public ResponseEntity<List<CardDTO>> cards(@PathVariable UUID pageId, @Valid @RequestBody AiCardsRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(aiService.cards(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    /** One grounded question in the study room. */
    @PostMapping("/ai/pages/{pageId}/chat")
    public ResponseEntity<ChatTurnDTO> chat(@PathVariable UUID pageId, @Valid @RequestBody ChatRequestDTO request) {
        return ResponseEntity.ok(studyService.chat(authenticatedUser.getAuthenticatedUser(), pageId, request.message()));
    }

    /** A studio output: overview, summary, study guide or quiz. */
    @PostMapping("/ai/pages/{pageId}/outputs")
    public ResponseEntity<StudyOutputDTO> generate(@PathVariable UUID pageId,
            @Valid @RequestBody GenerateOutputRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(studyService.generate(authenticatedUser.getAuthenticatedUser(), pageId, request.kind()));
    }
}
