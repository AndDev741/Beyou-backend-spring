package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.UUID;

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

import beyou.beyouapp.backend.domain.notebook.card.NotebookCardService;
import beyou.beyouapp.backend.domain.notebook.card.dto.CardDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.CreateCardRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.DueCardsResponseDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.FinishReviewResponseDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.ReviewRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.ReviewResponseDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.UpdateCardRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Study notebook: flashcards and reviewing them. */
@RestController
@RequestMapping("/notebook")
@RequiredArgsConstructor
public class NotebookCardController {

    private final NotebookCardService cardService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping("/pages/{pageId}/cards")
    public ResponseEntity<List<CardDTO>> list(@PathVariable UUID pageId) {
        return ResponseEntity.ok(cardService.list(authenticatedUser.getAuthenticatedUser(), pageId));
    }

    @PostMapping("/pages/{pageId}/cards")
    public ResponseEntity<CardDTO> create(@PathVariable UUID pageId, @Valid @RequestBody CreateCardRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cardService.create(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    @PatchMapping("/cards/{cardId}")
    public ResponseEntity<CardDTO> update(@PathVariable UUID cardId, @Valid @RequestBody UpdateCardRequestDTO request) {
        return ResponseEntity.ok(cardService.update(authenticatedUser.getAuthenticatedUser(), cardId, request));
    }

    @DeleteMapping("/cards/{cardId}")
    public ResponseEntity<Void> delete(@PathVariable UUID cardId) {
        cardService.delete(authenticatedUser.getAuthenticatedUser(), cardId);
        return ResponseEntity.noContent().build();
    }

    /** Today's queue: everywhere, or under {@code scopePageId}. */
    @GetMapping("/cards/due")
    public ResponseEntity<DueCardsResponseDTO> due(@RequestParam(required = false) UUID scopePageId) {
        return ResponseEntity.ok(cardService.due(authenticatedUser.getAuthenticatedUser(), scopePageId));
    }

    @PostMapping("/cards/{cardId}/review")
    public ResponseEntity<ReviewResponseDTO> review(@PathVariable UUID cardId, @Valid @RequestBody ReviewRequestDTO request) {
        return ResponseEntity.ok(cardService.review(authenticatedUser.getAuthenticatedUser(), cardId, request.rating()));
    }

    /** The end of a session: pays today's unpaid reviews, up to the daily cap. */
    @PostMapping("/reviews/finish")
    public ResponseEntity<FinishReviewResponseDTO> finish() {
        return ResponseEntity.ok(cardService.finish(authenticatedUser.getAuthenticatedUser()));
    }
}
