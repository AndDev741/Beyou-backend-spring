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

import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftService;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.DraftChoicesRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.RoadmapDraftRecordDTO;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.RoadmapDraftSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AiCardsRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AnswerDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.ChatRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CreateFromDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.ExplainRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.GenerateOutputRequestDTO;
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
    private final RoadmapDraftService draftService;
    private final NotebookStudyService studyService;
    private final AuthenticatedUser authenticatedUser;

    /**
     * "New topic with AI": stores the draft and answers at once, DRAFTING. The model writes it in
     * the background, and the client reads it back from {@code GET /notebook/drafts/{id}}.
     */
    @PostMapping("/ai/drafts")
    public ResponseEntity<RoadmapDraftRecordDTO> startDraft(@Valid @RequestBody RoadmapDraftRequestDTO request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(draftService.start(authenticatedUser.getAuthenticatedUser(), request));
    }

    /** Drafts again, from scratch or applying {@code changeRequest} to {@code previous}. */
    @PostMapping("/ai/drafts/{draftId}/redraft")
    public ResponseEntity<RoadmapDraftRecordDTO> redraft(@PathVariable UUID draftId,
            @Valid @RequestBody RoadmapDraftRequestDTO request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(draftService.redraft(authenticatedUser.getAuthenticatedUser(), draftId, request));
    }

    // The draft routes below call no model, so they live outside /notebook/ai and a client
    // polling a draft spends the ordinary read budget, not the AI one.

    @GetMapping("/drafts")
    public ResponseEntity<List<RoadmapDraftSummaryDTO>> drafts() {
        return ResponseEntity.ok(draftService.list(authenticatedUser.getAuthenticatedUser()));
    }

    @GetMapping("/drafts/{draftId}")
    public ResponseEntity<RoadmapDraftRecordDTO> draft(@PathVariable UUID draftId) {
        return ResponseEntity.ok(draftService.get(authenticatedUser.getAuthenticatedUser(), draftId));
    }

    /** The review dialog's ticks, saved as the person changes them. */
    @PutMapping("/drafts/{draftId}/choices")
    public ResponseEntity<RoadmapDraftRecordDTO> saveDraftChoices(@PathVariable UUID draftId,
            @Valid @RequestBody DraftChoicesRequestDTO request) {
        return ResponseEntity.ok(draftService.saveChoices(authenticatedUser.getAuthenticatedUser(), draftId, request.choices()));
    }

    @DeleteMapping("/drafts/{draftId}")
    public ResponseEntity<Void> deleteDraft(@PathVariable UUID draftId) {
        draftService.delete(authenticatedUser.getAuthenticatedUser(), draftId);
        return ResponseEntity.noContent().build();
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
