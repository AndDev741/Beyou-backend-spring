package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.notebook.StudyBoardEditor;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.MarkdownBlocks;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookRewards;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardEdgeDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardNodeDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateEdgeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The assistant's board tools against a real Postgres: names in, rows out, through the same
 * services the board on screen uses. Each test names the rule it guards.
 */
@Transactional
class StudyBoardEditorIT extends AbstractIntegrationTest {

    @Autowired private StudyBoardEditor editor;
    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private NotebookPageRepository pageRepository;
    @Autowired private UserRepository userRepository;

    private User user;
    private PageResponseDTO topic;

    @BeforeEach
    void seed() {
        user = newUser("board-editor");
        topic = topic("Software Engineer");
    }

    // ------------------------------------------------------------ finding the board

    @Test
    void aBoardIsNamedByItsIdTheRouteItIsOnOrItsExactTitleInAnyCase() {
        node(topic.id(), "Redes", 0);

        for (String ref : List.of(topic.id().toString(), "/notebook/" + topic.id() + "/board", "software ENGINEER")) {
            assertThat(editor.read(user, ref).get("boardPageId")).as(ref).isEqualTo(topic.id());
        }
    }

    @Test
    void twoPagesWithTheTitleAreRefusedNamingTheirTopicsInsteadOfPickingOne() {
        PageResponseDTO other = topic("Data Science");
        node(topic.id(), "Exercícios", 0);
        node(other.id(), "Exercícios", 0);

        assertThatThrownBy(() -> editor.read(user, "Exercícios"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"Software Engineer\"")
                .hasMessageContaining("\"Data Science\"");
    }

    @Test
    void anotherPersonsBoardIsRefusedEvenByItsId() {
        User stranger = newUser("board-stranger");

        assertThatThrownBy(() -> editor.read(stranger, topic.id().toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED);
    }

    // ------------------------------------------------------------ reading

    @Test
    void theBoardReadsInPathOrderWhateverWhereTheNodesWereDragged() {
        // Placed right to left, linked left to right: the path is what the links say.
        UUID c = node(topic.id(), "C", 0);
        UUID b = node(topic.id(), "B", 1);
        UUID a = node(topic.id(), "A", 2);
        edge(a, b);
        edge(b, c);

        Map<String, Object> board = editor.read(user, topic.id().toString());

        assertThat(titles(board)).containsExactly("A", "B", "C");
        assertThat(board.get("links")).isEqualTo(List.of(Map.of("from", "A", "to", "B"), Map.of("from", "B", "to", "C")));
    }

    @Test
    void aNameThatMatchesNoNodeListsTheNodesThatAreThere() {
        node(topic.id(), "Redes", 0);
        node(topic.id(), "Sistemas Operacionais", 1);

        assertThatThrownBy(() -> editor.editNode(user, topic.id().toString(), "Compiladores", "X", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"Redes\"")
                .hasMessageContaining("\"Sistemas Operacionais\"");
    }

    // ------------------------------------------------------------ adding

    @Test
    void anAddedNodeTakesTheNextFreeGridCellAndAfterLinksIt() {
        // A full first row: the next cell starts the second row, not a fourth column at y 80.
        node(topic.id(), "A", 0);
        node(topic.id(), "B", 1);
        node(topic.id(), "C", 2);

        editor.addNode(user, topic.id().toString(), "D", "c");

        BoardResponseDTO board = boardService.board(user, topic.id());
        BoardNodeDTO d = byTitle(board, "D");
        assertThat(d.x()).isEqualTo(NotebookBoardService.gridCell(3).x());
        assertThat(d.y()).isEqualTo(NotebookBoardService.gridCell(3).y());
        assertThat(board.edges()).extracting(BoardEdgeDTO::source, BoardEdgeDTO::target)
                .containsExactly(tuple(byTitle(board, "C").id(), d.id()));
    }

    @Test
    void anUnknownAfterNodeAddsNothing() {
        node(topic.id(), "A", 0);

        assertThatThrownBy(() -> editor.addNode(user, topic.id().toString(), "B", "Nowhere"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(boardService.board(user, topic.id()).nodes()).hasSize(1);
    }

    @Test
    void addingTheFirstNodeToAPageWithoutABoardBlockGivesItOne() {
        // A node's page starts with no document, so its board would have nowhere to be drawn.
        UUID redes = pageOf(node(topic.id(), "Redes", 0));
        assertThat(pageRepository.findById(redes).orElseThrow().getContent()).isNull();

        editor.addNode(user, redes.toString(), "TCP", null);
        editor.addNode(user, redes.toString(), "UDP", null);

        String content = pageRepository.findById(redes).orElseThrow().getContent();
        assertThat(content.split(MarkdownBlocks.BOARD_BLOCK_TYPE, -1)).hasSize(2);
    }

    // ------------------------------------------------------------ editing

    @Test
    void renamingANodeRenamesItsPageAndTheIconComesFromTheCatalog() {
        UUID redes = pageOf(node(topic.id(), "Redes", 0));

        editor.editNode(user, topic.id().toString(), "redes", "Redes de Computadores", "lucide:book-open");

        NotebookPage page = pageRepository.findById(redes).orElseThrow();
        assertThat(page.getTitle()).isEqualTo("Redes de Computadores");
        assertThat(page.getIcon()).isEqualTo("lucide:book-open");

        assertThatThrownBy(() -> editor.editNode(user, topic.id().toString(), "Redes de Computadores", null, "not-an-icon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("icon catalog");

        editor.editNode(user, topic.id().toString(), "Redes de Computadores", null, "none");
        assertThat(pageRepository.findById(redes).orElseThrow().getIcon()).isNull();
    }

    @Test
    void doneFromTheAssistantPaysTheSameXpAsAClickAndABlankNodeMeansTheBoardPage() {
        UUID redes = pageOf(node(topic.id(), "Redes", 0));
        // A second, unfinished node, so finishing Redes does not also finish the topic and pay twice.
        node(topic.id(), "Sistemas", 1);
        double xpBefore = userXp();

        Map<String, Object> done = editor.setStatus(user, topic.id().toString(), "Redes", "done");

        assertThat(done.get("xpEarned")).isEqualTo(NotebookRewards.PAGE_DONE_XP);
        assertThat(userXp() - xpBefore).isEqualTo(NotebookRewards.PAGE_DONE_XP);
        assertThat(pageRepository.findById(redes).orElseThrow().getStatus()).isEqualTo(NotebookStatus.DONE);

        editor.setStatus(user, topic.id().toString(), null, "studying");
        NotebookPage board = pageRepository.findById(topic.id()).orElseThrow();
        assertThat(board.getStatus()).isEqualTo(NotebookStatus.STUDYING);
        assertThat(board.isStatusManual()).isTrue();

        assertThatThrownBy(() -> editor.setStatus(user, topic.id().toString(), "Redes", "finished"))
                .hasMessageContaining("TO_STUDY, STUDYING, DONE or AUTO");
    }

    @Test
    void linksAreAddedAndRemovedByTitleAndAReversedRemovalSaysSo() {
        node(topic.id(), "A", 0);
        node(topic.id(), "B", 1);

        editor.connect(user, topic.id().toString(), "A", "B");
        assertThatThrownBy(() -> editor.disconnect(user, topic.id().toString(), "B", "A"))
                .hasMessageContaining("other way");

        editor.disconnect(user, topic.id().toString(), "A", "B");
        assertThat(boardService.board(user, topic.id()).edges()).isEmpty();
    }

    @Test
    void removingANodeKeepsItsPageUnlessTheDeleteWasAskedFor() {
        UUID kept = pageOf(node(topic.id(), "Kept", 0));
        UUID gone = pageOf(node(topic.id(), "Gone", 1));

        editor.remove(user, topic.id().toString(), "Kept", false);
        editor.remove(user, topic.id().toString(), "Gone", true);

        assertThat(boardService.board(user, topic.id()).nodes()).isEmpty();
        assertThat(pageRepository.findById(kept)).isPresent();
        assertThat(pageRepository.findById(gone)).isEmpty();
    }

    // ------------------------------------------------------------ restructuring

    @Test
    void reorderingMakesOnePathOnTheGridAndReplacesEveryLink() {
        UUID a = node(topic.id(), "A", 0);
        UUID b = node(topic.id(), "B", 1);
        UUID c = node(topic.id(), "C", 2);
        // A branch: A before B and A before C.
        edge(a, b);
        edge(a, c);

        editor.reorder(user, topic.id().toString(), List.of("C", "a", "B"));

        BoardResponseDTO board = boardService.board(user, topic.id());
        assertThat(board.edges()).extracting(BoardEdgeDTO::source, BoardEdgeDTO::target).containsExactlyInAnyOrder(
                tuple(c, a), tuple(a, b));
        assertThat(byTitle(board, "C").x()).isEqualTo(NotebookBoardService.gridCell(0).x());
        assertThat(byTitle(board, "A").x()).isEqualTo(NotebookBoardService.gridCell(1).x());
        assertThat(byTitle(board, "B").x()).isEqualTo(NotebookBoardService.gridCell(2).x());
    }

    @Test
    void anOrderThatLeavesANodeOutIsRefusedAndChangesNothing() {
        UUID a = node(topic.id(), "A", 0);
        UUID b = node(topic.id(), "B", 1);
        node(topic.id(), "C", 2);
        edge(a, b);

        assertThatThrownBy(() -> editor.reorder(user, topic.id().toString(), List.of("B", "A")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"C\"");
        assertThatThrownBy(() -> editor.reorder(user, topic.id().toString(), List.of("A", "B", "C", "a")))
                .hasMessageContaining("twice");

        assertThat(boardService.board(user, topic.id()).edges()).extracting(BoardEdgeDTO::source).containsExactly(a);
    }

    // ------------------------------------------------------------ notes

    @Test
    void notesGoAtTheEndOfTheNodesPageOrOfTheBoardPage() {
        UUID redes = pageOf(node(topic.id(), "Redes", 0));

        editor.appendNotes(user, topic.id().toString(), "Redes", "## Camadas\n\n- Física\n- Enlace");
        editor.appendNotes(user, topic.id().toString(), null, "Começar por **Redes**.");

        assertThat(pageRepository.findById(redes).orElseThrow().getContentText()).contains("Camadas", "Enlace");
        assertThat(pageRepository.findById(topic.id()).orElseThrow().getContentText()).contains("Começar por Redes");
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static List<String> titles(Map<String, Object> board) {
        return ((List<Map<String, Object>>) board.get("nodes")).stream().map(n -> (String) n.get("title")).toList();
    }

    private PageResponseDTO topic(String title) {
        return pageService.createTopic(user, new CreateTopicRequestDTO(title, null, null, null, null, null));
    }

    /** A page node on grid cell {@code cell}; returns the node id. */
    private UUID node(UUID boardPageId, String title, int cell) {
        NotebookBoardService.GridCell at = NotebookBoardService.gridCell(cell);
        return boardService.addNode(user, boardPageId,
                new CreateNodeRequestDTO(NotebookNodeKind.PAGE, title, null, null, at.x(), at.y(), null, null, null)).node().id();
    }

    private void edge(UUID source, UUID target) {
        boardService.addEdge(user, topic.id(), new CreateEdgeRequestDTO(source, target));
    }

    private UUID pageOf(UUID nodeId) {
        return boardService.board(user, topic.id()).nodes().stream()
                .filter(n -> n.id().equals(nodeId)).findFirst().orElseThrow().pageId();
    }

    private static BoardNodeDTO byTitle(BoardResponseDTO board, String title) {
        return board.nodes().stream().filter(n -> n.title().equals(title)).findFirst().orElseThrow();
    }

    private double userXp() {
        return userRepository.findById(user.getId()).orElseThrow().getXpProgress().getXp();
    }

    private User newUser(String tag) {
        User u = new User();
        u.setName(tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
