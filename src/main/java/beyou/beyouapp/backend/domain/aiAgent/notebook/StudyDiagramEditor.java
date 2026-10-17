package beyou.beyouapp.backend.domain.aiAgent.notebook;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSanitizer;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;
import beyou.beyouapp.backend.domain.notebook.dto.AppendRequestDTO;
import beyou.beyouapp.backend.user.User;

/**
 * The assistant's diagrams: one drawn in the chat and kept nowhere ({@link #show}), or one added
 * to the end of a notebook page ({@link #add}).
 *
 * <p>Both clean the model's spec first and refuse one with nothing to draw, with a message that
 * says what to send, so the model can try again in the same turn. A page is found the way the
 * board tools find one (id, route or exact title, refusing to guess between two), and the block
 * goes in through the same append as "Save to page", with its compare-and-set, so an editor open
 * on that page merges it instead of overwriting it.
 */
@Service
public class StudyDiagramEditor {

    private final StudyBoardEditor boardEditor;
    private final NotebookPageService pageService;

    public StudyDiagramEditor(StudyBoardEditor boardEditor, NotebookPageService pageService) {
        this.boardEditor = boardEditor;
        this.pageService = pageService;
    }

    /** Where a diagram went: the clean spec and the page that holds it now. */
    public record Added(DiagramSpec diagram, UUID pageId, String pageTitle) {
    }

    /** The diagram as it will be drawn. Nothing is saved. */
    public DiagramSpec show(DiagramSpec diagram) {
        return clean(diagram);
    }

    @Transactional
    public Added add(User user, String page, DiagramSpec diagram) {
        DiagramSpec clean = clean(diagram);
        if (page == null || page.isBlank()) {
            throw new IllegalArgumentException("Say which page gets the diagram: the page id from the route the user is "
                    + "on (/notebook/<id>), or the page's exact title. To only show a diagram, use showDiagram");
        }
        NotebookPage target = boardEditor.boardPage(user, page);
        pageService.append(user, target.getId(), new AppendRequestDTO(null, clean));
        return new Added(clean, target.getId(), target.getTitle());
    }

    private static DiagramSpec clean(DiagramSpec diagram) {
        if (diagram == null) {
            throw new IllegalArgumentException("Missing diagram: send an object with kind, title, nodes "
                    + "(each with an id and a label) and links between those ids");
        }
        return DiagramSanitizer.clean(diagram).orElseThrow(() -> new IllegalArgumentException(
                "Nothing to draw: a diagram needs at least two nodes with a label. Send nodes like "
                        + "[{\"id\":\"n1\",\"label\":\"Start\"},{\"id\":\"n2\",\"label\":\"Next step\"}] and links "
                        + "between their ids"));
    }
}
