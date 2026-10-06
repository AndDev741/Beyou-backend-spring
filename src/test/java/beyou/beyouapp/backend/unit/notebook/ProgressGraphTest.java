package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNode;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.user.User;

/** The graph walks, including the loop that the board service refuses but the graph must survive. */
class ProgressGraphTest {

    private final User user = new User();

    @Test
    void aLoopDoesNotHangAndCountsEachLeafOnce() {
        NotebookPage a = page(NotebookStatus.TO_STUDY);
        NotebookPage b = page(NotebookStatus.DONE);
        NotebookPage leaf = page(NotebookStatus.DONE);
        // a shows b, b shows a and the leaf: a loop, as if it had got in some other way.
        ProgressGraph graph = ProgressGraph.of(List.of(a, b, leaf),
                List.of(node(a, b, 0), node(b, a, 0), node(b, leaf, 240)));

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
                List.of(node(board, done, 0), node(board, fresh, 240)));

        assertThat(graph.derivedStatus(board.getId())).isEqualTo(NotebookStatus.STUDYING);
        assertThat(graph.derivedStatus(done.getId())).isNull();
    }

    @Test
    void theNextLeafIsTheFirstBeingStudiedElseTheFirstNotStarted() {
        NotebookPage board = page(NotebookStatus.STUDYING);
        NotebookPage first = page(NotebookStatus.TO_STUDY);
        NotebookPage second = page(NotebookStatus.STUDYING);
        ProgressGraph graph = ProgressGraph.of(List.of(board, first, second),
                List.of(node(board, first, 0), node(board, second, 240)));

        assertThat(graph.nextLeaf(board.getId())).isEqualTo(second.getId());
    }

    private NotebookPage page(NotebookStatus status) {
        NotebookPage page = NotebookPage.topic(user, "p", Instant.now());
        page.setId(UUID.randomUUID());
        page.setStatus(status);
        return page;
    }

    private static NotebookBoardNode node(NotebookPage board, NotebookPage page, double x) {
        NotebookBoardNode node = new NotebookBoardNode();
        node.setId(UUID.randomUUID());
        node.setBoardPageId(board.getId());
        node.setPageId(page.getId());
        node.setKind(NotebookNodeKind.PAGE);
        node.setX(x);
        return node;
    }
}
