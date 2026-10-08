package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookProgressService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.AppendRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.ContentSavedDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChoice;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Two requests on one page, the second committing while the first still holds the row it loaded.
 *
 * <p>Found by the e2e suite: the study room's "save to page" landed while the page screen's poll
 * was opening the same page, and the open's flush wrote the whole row back as it had loaded it,
 * document included. The append was gone with no error anywhere. Each test here replays one such
 * interleaving on a single thread: the outer transaction loads the page, an inner REQUIRES_NEW
 * transaction writes and commits, then the outer one finishes and commits.
 *
 * <p>Not {@code @Transactional}: the inner write has to commit for real, which a test-managed
 * transaction would not allow. Every test makes its own user.
 */
class NotebookConcurrentWritesIT extends AbstractIntegrationTest {

    private static final String APPENDED = "It is the smallest key larger than the one removed.";

    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookProgressService progressService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private NotebookPageRepository pageRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate outer;
    private TransactionTemplate inner;
    private User user;
    private UUID topicId;

    @BeforeEach
    void seed() {
        outer = new TransactionTemplate(transactionManager);
        inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        user = newUser("notebook-races");
        topicId = pageService.createTopic(user,
                new CreateTopicRequestDTO("Data Structures", null, null, null, null, null)).id();
    }

    /** The race the e2e run hit: opening a page must not undo what was saved meanwhile. */
    @Test
    void openingAPageKeepsTheTextSavedWhileItWasLoading() {
        outer.executeWithoutResult(status -> {
            pageRepository.findById(topicId).orElseThrow();
            inner.executeWithoutResult(s -> pageService.append(user, topicId, new AppendRequestDTO(APPENDED)));
            pageService.open(user, topicId);
        });

        NotebookPage stored = pageRepository.findById(topicId).orElseThrow();
        assertThat(stored.getContentText()).contains(APPENDED);
        assertThat(stored.getLastOpenedAt()).isNotNull();
    }

    /**
     * Finishing the topic's only node moves the topic's status. That write must carry the status
     * and nothing else, or a status change becomes a way to revert the topic's notes.
     */
    @Test
    void aStatusMovingUpKeepsTheTextSavedWhileItWasLoading() {
        UUID nodePageId = boardService.addNode(user, topicId,
                new CreateNodeRequestDTO(null, "Trees", null, null, 0.0, 0.0, null, null)).node().pageId();

        outer.executeWithoutResult(status -> {
            progressService.graphFor(user.getId());
            inner.executeWithoutResult(s -> pageService.append(user, topicId, new AppendRequestDTO(APPENDED)));
            pageService.setStatus(user, nodePageId, new SetStatusRequestDTO(StatusChoice.DONE));
        });

        NotebookPage topic = pageRepository.findById(topicId).orElseThrow();
        assertThat(topic.getStatus().name()).isEqualTo("DONE");
        assertThat(topic.getContentText()).contains(APPENDED);
    }

    // ------------------------------------------------------------ the document's revision

    private static String doc(String text) {
        return "[{\"id\":\"b1\",\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
                + text + "\",\"styles\":{}}],\"children\":[]}]";
    }

    private long revision() {
        return pageRepository.findById(topicId).orElseThrow().getContentRevision();
    }

    /**
     * The overwrite this fixes: the page open in an editor, notes appended meanwhile (the
     * assistant, the study room, another device), then the editor's autosave. It used to write
     * its older document over the appended notes with no error anywhere.
     */
    @Test
    void aSaveFromBeforeAnAppendIsRefusedAndTheAppendStays() {
        long read = pageService.open(user, topicId).contentRevision();
        pageService.append(user, topicId, new AppendRequestDTO(APPENDED));

        assertThatThrownBy(() -> pageService.saveContent(user, topicId, new UpdateContentRequestDTO(doc("Typed before"), read)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_CONTENT_CONFLICT);
        assertThat(pageRepository.findById(topicId).orElseThrow().getContentText()).contains(APPENDED);
    }

    @Test
    void eachSaveMovesTheRevisionOnAndTheNextStartsFromIt() {
        long read = pageService.open(user, topicId).contentRevision();

        ContentSavedDTO first = pageService.saveContent(user, topicId, new UpdateContentRequestDTO(doc("One"), read));
        ContentSavedDTO second = pageService.saveContent(user, topicId, new UpdateContentRequestDTO(doc("Two"), first.contentRevision()));

        assertThat(first.contentRevision()).isEqualTo(read + 1);
        assertThat(second.contentRevision()).isEqualTo(read + 2);
        assertThat(pageService.open(user, topicId).contentRevision()).isEqualTo(read + 2);
    }

    /** Two editors that read the same revision: the first save lands, the second is told. */
    @Test
    void twoSavesFromTheSameRevisionLetOnlyTheFirstThrough() {
        long read = revision();

        outer.executeWithoutResult(status -> {
            pageRepository.findById(topicId).orElseThrow();
            inner.executeWithoutResult(s -> pageService.saveContent(user, topicId, new UpdateContentRequestDTO(doc("Phone"), read)));
            assertThatThrownBy(() -> pageService.saveContent(user, topicId, new UpdateContentRequestDTO(doc("Computer"), read)))
                    .extracting(e -> ((BusinessException) e).getErrorKey())
                    .isEqualTo(ErrorKey.NOTEBOOK_CONTENT_CONFLICT);
            status.setRollbackOnly();
        });

        assertThat(pageRepository.findById(topicId).orElseThrow().getContentText()).isEqualTo("Phone");
        assertThat(revision()).isEqualTo(read + 1);
    }

    /**
     * A server-side append that read the document before a save committed reads it again and
     * appends to the saved one, instead of writing its stale copy back.
     */
    @Test
    void anAppendThatReadAnOldDocumentReadsAgainAndKeepsTheSave() {
        long read = revision();

        outer.executeWithoutResult(status -> {
            pageRepository.findById(topicId).orElseThrow();
            inner.executeWithoutResult(s -> pageService.saveContent(user, topicId, new UpdateContentRequestDTO(doc("Saved meanwhile"), read)));
            pageService.append(user, topicId, new AppendRequestDTO(APPENDED));
        });

        NotebookPage stored = pageRepository.findById(topicId).orElseThrow();
        assertThat(stored.getContentText()).contains("Saved meanwhile").contains(APPENDED);
        assertThat(stored.getContentRevision()).isEqualTo(read + 2);
    }

    /** A tab opened before revisions existed sends none, and keeps saving over whatever is there. */
    @Test
    void aClientWithoutRevisionsStillSaves() {
        long read = revision();

        ContentSavedDTO saved = pageService.saveContent(user, topicId, new UpdateContentRequestDTO(doc("Old tab"), null));

        assertThat(saved.contentRevision()).isEqualTo(read + 1);
        assertThat(pageRepository.findById(topicId).orElseThrow().getContentText()).isEqualTo("Old tab");
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
