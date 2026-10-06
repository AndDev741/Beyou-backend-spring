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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.dto.AppendRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.ContentSavedDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreatePageRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.HomeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageSearchHitDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.TopicLinksRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.TreeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdatePageRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Study notebook: topics and pages.
 *
 * <p>Every route is authenticated by the default rule, and the rate limiter files writes under
 * {@code write:} and reads under {@code read:} with no configuration. The LLM-backed routes live
 * in {@link NotebookAiController} under {@code /notebook/ai}, where they get their own tier.
 */
@RestController
@RequestMapping("/notebook")
@RequiredArgsConstructor
public class NotebookController {

    private final NotebookPageService pageService;
    private final AuthenticatedUser authenticatedUser;

    /** The home screen in one call: topic cards, the page to continue, the cards due. */
    @GetMapping("/home")
    public ResponseEntity<HomeResponseDTO> home() {
        return ResponseEntity.ok(pageService.home(authenticatedUser.getAuthenticatedUser()));
    }

    @PostMapping("/topics")
    public ResponseEntity<PageResponseDTO> createTopic(@Valid @RequestBody CreateTopicRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(pageService.createTopic(authenticatedUser.getAuthenticatedUser(), request));
    }

    @GetMapping("/topics/{topicId}/tree")
    public ResponseEntity<TreeResponseDTO> tree(@PathVariable UUID topicId) {
        return ResponseEntity.ok(pageService.tree(authenticatedUser.getAuthenticatedUser(), topicId));
    }

    /** The goal, category and habit a topic is linked to, replaced as a set. */
    @PutMapping("/topics/{topicId}/links")
    public ResponseEntity<PageResponseDTO> setLinks(@PathVariable UUID topicId,
            @RequestBody TopicLinksRequestDTO request) {
        return ResponseEntity.ok(pageService.setLinks(authenticatedUser.getAuthenticatedUser(), topicId, request));
    }

    /** A page by title, for the "link an existing page" picker. */
    @GetMapping("/pages/search")
    public ResponseEntity<List<PageSearchHitDTO>> search(@RequestParam(name = "q", defaultValue = "") String query) {
        return ResponseEntity.ok(pageService.search(authenticatedUser.getAuthenticatedUser(), query));
    }

    /** The page screen. Also records the page as the one the home's "Continue" card offers. */
    @GetMapping("/pages/{pageId}")
    public ResponseEntity<PageResponseDTO> page(@PathVariable UUID pageId) {
        return ResponseEntity.ok(pageService.open(authenticatedUser.getAuthenticatedUser(), pageId));
    }

    @PostMapping("/pages")
    public ResponseEntity<PageResponseDTO> createPage(@Valid @RequestBody CreatePageRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(pageService.createPage(authenticatedUser.getAuthenticatedUser(), request));
    }

    @PatchMapping("/pages/{pageId}")
    public ResponseEntity<PageResponseDTO> update(@PathVariable UUID pageId,
            @Valid @RequestBody UpdatePageRequestDTO request) {
        return ResponseEntity.ok(pageService.update(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    /** The editor's autosave: the whole document. */
    @PutMapping("/pages/{pageId}/content")
    public ResponseEntity<ContentSavedDTO> saveContent(@PathVariable UUID pageId,
            @Valid @RequestBody UpdateContentRequestDTO request) {
        return ResponseEntity.ok(pageService.saveContent(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    @PutMapping("/pages/{pageId}/status")
    public ResponseEntity<StatusChangeResponseDTO> setStatus(@PathVariable UUID pageId,
            @Valid @RequestBody SetStatusRequestDTO request) {
        return ResponseEntity.ok(pageService.setStatus(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    /** "Save to page": markdown appended to the document as blocks. */
    @PostMapping("/pages/{pageId}/append")
    public ResponseEntity<PageResponseDTO> append(@PathVariable UUID pageId,
            @Valid @RequestBody AppendRequestDTO request) {
        return ResponseEntity.ok(pageService.append(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    /** Deletes the page and its subtree. */
    @DeleteMapping("/pages/{pageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID pageId) {
        pageService.delete(authenticatedUser.getAuthenticatedUser(), pageId);
        return ResponseEntity.noContent().build();
    }
}
