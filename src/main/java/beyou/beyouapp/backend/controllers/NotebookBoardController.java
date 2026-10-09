package beyou.beyouapp.backend.controllers;

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

import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardEdgeDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardOrderRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateEdgeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.LayoutRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.UpdateNodeRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Study notebook: the roadmap board on a page. The board is addressed through its page; nodes
 * and edges have their own ids once they exist.
 */
@RestController
@RequestMapping("/notebook")
@RequiredArgsConstructor
public class NotebookBoardController {

    private final NotebookBoardService boardService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping("/pages/{pageId}/board")
    public ResponseEntity<BoardResponseDTO> board(@PathVariable UUID pageId) {
        return ResponseEntity.ok(boardService.board(authenticatedUser.getAuthenticatedUser(), pageId));
    }

    @PostMapping("/pages/{pageId}/board/nodes")
    public ResponseEntity<BoardChangeResponseDTO> addNode(@PathVariable UUID pageId,
            @Valid @RequestBody CreateNodeRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardService.addNode(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    @PatchMapping("/board/nodes/{nodeId}")
    public ResponseEntity<BoardChangeResponseDTO> updateNode(@PathVariable UUID nodeId,
            @Valid @RequestBody UpdateNodeRequestDTO request) {
        return ResponseEntity.ok(boardService.updateNode(authenticatedUser.getAuthenticatedUser(), nodeId, request));
    }

    /** Several positions at once: "Tidy up", or a multi-node drag. */
    @PutMapping("/pages/{pageId}/board/layout")
    public ResponseEntity<Void> layout(@PathVariable UUID pageId, @Valid @RequestBody LayoutRequestDTO request) {
        boardService.layout(authenticatedUser.getAuthenticatedUser(), pageId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Makes the board one path through every page node, in the order given: what the phone's
     * reorder sends. Edges the person drew are replaced by the chain. Answers the board as it is
     * now.
     */
    @PutMapping("/pages/{pageId}/board/order")
    public ResponseEntity<BoardResponseDTO> order(@PathVariable UUID pageId, @Valid @RequestBody BoardOrderRequestDTO request) {
        User user = authenticatedUser.getAuthenticatedUser();
        boardService.restructure(user, pageId, request.order());
        return ResponseEntity.ok(boardService.board(user, pageId));
    }

    /**
     * Removes a node. {@code deletePage=true} also deletes its page, when the page lives under
     * this board; a linked page is only unlinked.
     */
    @DeleteMapping("/board/nodes/{nodeId}")
    public ResponseEntity<BoardChangeResponseDTO> deleteNode(@PathVariable UUID nodeId,
            @RequestParam(defaultValue = "false") boolean deletePage) {
        return ResponseEntity.ok(boardService.deleteNode(authenticatedUser.getAuthenticatedUser(), nodeId, deletePage));
    }

    @PostMapping("/pages/{pageId}/board/edges")
    public ResponseEntity<BoardEdgeDTO> addEdge(@PathVariable UUID pageId,
            @Valid @RequestBody CreateEdgeRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardService.addEdge(authenticatedUser.getAuthenticatedUser(), pageId, request));
    }

    @DeleteMapping("/board/edges/{edgeId}")
    public ResponseEntity<Void> deleteEdge(@PathVariable UUID edgeId) {
        boardService.deleteEdge(authenticatedUser.getAuthenticatedUser(), edgeId);
        return ResponseEntity.noContent().build();
    }
}
