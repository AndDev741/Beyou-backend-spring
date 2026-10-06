package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.source.dto.AddLinkRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.AddTextRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.PassageDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.SourceDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.UpdateSourceRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Study notebook: sources. The three POSTs that add one share the {@code notebook-source:} rate
 * tier, because each starts a background read (and, with a model configured, paid embeddings).
 * A new source answers 202: it exists, and it is being read.
 */
@RestController
@RequestMapping("/notebook")
@RequiredArgsConstructor
public class NotebookSourceController {

    private final NotebookSourceService sourceService;
    private final AuthenticatedUser authenticatedUser;

    /** The sources this page can read: its own and its ancestors'. */
    @GetMapping("/pages/{pageId}/sources")
    public ResponseEntity<List<SourceDTO>> list(@PathVariable UUID pageId) {
        return ResponseEntity.ok(sourceService.list(authenticatedUser.getAuthenticatedUser(), pageId));
    }

    @PostMapping(value = "/pages/{pageId}/sources/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SourceDTO> addPdf(@PathVariable UUID pageId, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sourceService.addPdf(authenticatedUser.getAuthenticatedUser(), pageId, file));
    }

    @PostMapping("/pages/{pageId}/sources/link")
    public ResponseEntity<SourceDTO> addLink(@PathVariable UUID pageId, @Valid @RequestBody AddLinkRequestDTO request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sourceService.addLink(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    @PostMapping("/pages/{pageId}/sources/text")
    public ResponseEntity<SourceDTO> addText(@PathVariable UUID pageId, @Valid @RequestBody AddTextRequestDTO request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sourceService.addText(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    @PatchMapping("/sources/{sourceId}")
    public ResponseEntity<SourceDTO> update(@PathVariable UUID sourceId, @Valid @RequestBody UpdateSourceRequestDTO request) {
        return ResponseEntity.ok(sourceService.setEnabled(authenticatedUser.getAuthenticatedUser(), sourceId, request.enabled()));
    }

    @DeleteMapping("/sources/{sourceId}")
    public ResponseEntity<Void> delete(@PathVariable UUID sourceId) {
        sourceService.delete(authenticatedUser.getAuthenticatedUser(), sourceId);
        return ResponseEntity.noContent().build();
    }

    /** One cited passage with its neighbours: "Open at page N". */
    @GetMapping("/sources/{sourceId}/passages/{chunkId}")
    public ResponseEntity<PassageDTO> passage(@PathVariable UUID sourceId, @PathVariable UUID chunkId) {
        return ResponseEntity.ok(sourceService.passage(authenticatedUser.getAuthenticatedUser(), sourceId, chunkId));
    }
}
