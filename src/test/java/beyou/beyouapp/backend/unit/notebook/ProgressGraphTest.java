package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardEdge;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNode;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.user.User;

/**
 * The graph walks, including the loop that the board service refuses but the graph must survive,
 * and the order a board's pages are listed in.
 */
class ProgressGraphTest {

    private final User user = new User();

    @Test
    void aLoopDoesNotHangAndCountsEachLeafOnce() {
        NotebookPage a = page(NotebookStatus.TO_STUDY);
        NotebookPage b = page(NotebookStatus.DONE);
        NotebookPage leaf = page(NotebookStatus.DONE);
        // a shows b, b shows a and the leaf: a loop, as if it had got in some other way.
        ProgressGraph graph = ProgressGraph.of(List.of(a, b, leaf),
                List.of(node(a, b, 0, 0), node(b, a, 0, 0), node(b, leaf, 240, 0)), List.of());

        assertThat(graph.progressOf(a.getId()).total()).isEqualTo(1);
        assertThat(graph.progressOf(a.getId()).done()).isEqualTo(1);
        assertThat(graph.reaches(b.getId(), a.getId())).isTrue();
    }

    @Test
    void theDerivedStatusFollowsTheNodes() {
        NotebookPage board = page(NotebookStatus.TO_STUDY);
        NotebookPage done = page(NotebookStatus.DONE);
        NotebookPage fresh = page(NotebookStatus.TO_STUDY);
        ProgressGraph graph = ProgressGraph.of(List.of(board, done, fresh),
                List.of(node(board, done, 0, 0), node(board, fresh, 240, 0)), List.of());

        assertThat(graph.derivedStatus(board.getId())).isEqualTo(NotebookStatus.STUDYING);
        assertThat(graph.derivedStatus(done.getId())).isNull();
    }

    @Test
    void theNextLeafIsTheFirstBeingStudiedElseTheFirstNotStarted() {
        NotebookPage board = page(NotebookStatus.STUDYING);
        NotebookPage first = page(NotebookStatus.TO_STUDY);
        NotebookPage second = page(NotebookStatus.STUDYING);
        ProgressGraph graph = ProgressGraph.of(List.of(board, first, second),
                List.of(node(board, first, 0, 0), node(board, second, 240, 0)), List.of());

        assertThat(graph.nextLeaf(board.getId())).isEqualTo(second.getId());
    }

    /**
     * A board drawn three to a row, as the server and "Tidy up" lay it out, is read row by row.
     * The graph used to sort by x before y, which reads it column by column: the sidebar listed
     * the first node of the second row second, and "next" skipped the rest of the first row.
     */
    @Test
    void aBoardIsReadRowByRowNotColumnByColumn() {
        NotebookPage board = page(NotebookStatus.STUDYING);
        List<NotebookPage> kids = List.of(page(NotebookStatus.DONE), page(NotebookStatus.TO_STUDY),
                page(NotebookStatus.TO_STUDY), page(NotebookStatus.TO_STUDY), page(NotebookStatus.TO_STUDY),
                page(NotebookStatus.TO_STUDY));
        List<NotebookBoardNode> nodes = new ArrayList<>();
        for (int i = 0; i < kids.size(); i++) {
            nodes.add(node(board, kids.get(i), 40 + (i % 3) * 240, (i / 3) * 140));
        }
        List<NotebookPage> pages = new ArrayList<>(kids);
        pages.add(board);

        ProgressGraph graph = ProgressGraph.of(pages, nodes, List.of());

        assertThat(graph.childrenOf(board.getId())).containsExactlyElementsOf(ids(kids));
        assertThat(graph.nextLeaf(board.getId())).isEqualTo(kids.get(1).getId());
    }

    /** A node dragged a few pixels above its neighbours still belongs to their row. */
    @Test
    void aNodeNudgedOffItsRowStaysInIt() {
        NotebookPage board = page(NotebookStatus.TO_STUDY);
        NotebookPage left = page(NotebookStatus.TO_STUDY);
        NotebookPage right = page(NotebookStatus.TO_STUDY);
        NotebookPage below = page(NotebookStatus.TO_STUDY);

        ProgressGraph graph = ProgressGraph.of(List.of(board, left, right, below),
                List.of(node(board, left, 40, 0), node(board, right, 280, -20), node(board, below, 40, 140)),
                List.of());

        assertThat(graph.childrenOf(board.getId())).containsExactly(left.getId(), right.getId(), below.getId());
    }

    /** An arrow says "study this before that", and that wins over where the nodes were put. */
    @Test
    void theArrowsComeBeforeThePlace() {
        NotebookPage board = page(NotebookStatus.TO_STUDY);
        NotebookPage placedFirst = page(NotebookStatus.TO_STUDY);
        NotebookPage studiedFirst = page(NotebookStatus.TO_STUDY);
        NotebookBoardNode left = node(board, placedFirst, 40, 0);
        NotebookBoardNode right = node(board, studiedFirst, 280, 0);

        ProgressGraph graph = ProgressGraph.of(List.of(board, placedFirst, studiedFirst), List.of(left, right),
                List.of(edge(board, right, left)));

        assertThat(graph.childrenOf(board.getId())).containsExactly(studiedFirst.getId(), placedFirst.getId());
        assertThat(graph.nextLeaf(board.getId())).isEqualTo(studiedFirst.getId());
    }

    /** A loop of arrows cannot be followed, so it is broken where the person put its first node. */
    @Test
    void aLoopOfArrowsStillPlacesEveryNode() {
        NotebookPage board = page(NotebookStatus.TO_STUDY);
        NotebookPage a = page(NotebookStatus.TO_STUDY);
        NotebookPage b = page(NotebookStatus.TO_STUDY);
        NotebookBoardNode first = node(board, a, 40, 0);
        NotebookBoardNode second = node(board, b, 280, 0);

        ProgressGraph graph = ProgressGraph.of(List.of(board, a, b), List.of(second, first),
                List.of(edge(board, first, second), edge(board, second, first)));

        assertThat(graph.childrenOf(board.getId())).containsExactly(a.getId(), b.getId());
    }

    private NotebookPage page(NotebookStatus status) {
        NotebookPage page = NotebookPage.topic(user, "p", Instant.now());
        page.setId(UUID.randomUUID());
        page.setStatus(status);
        return page;
    }

    private static NotebookBoardNode node(NotebookPage board, NotebookPage page, double x, double y) {
        NotebookBoardNode node = new NotebookBoardNode();
        node.setId(UUID.randomUUID());
        node.setBoardPageId(board.getId());
        node.setPageId(page.getId());
        node.setKind(NotebookNodeKind.PAGE);
        node.setX(x);
        node.setY(y);
        return node;
    }

    private static NotebookBoardEdge edge(NotebookPage board, NotebookBoardNode source, NotebookBoardNode target) {
        NotebookBoardEdge edge = new NotebookBoardEdge();
        edge.setId(UUID.randomUUID());
        edge.setBoardPageId(board.getId());
        edge.setSourceNodeId(source.getId());
        edge.setTargetNodeId(target.getId());
        return edge;
    }

    private static List<UUID> ids(List<NotebookPage> pages) {
        return pages.stream().map(NotebookPage::getId).toList();
    }
}
