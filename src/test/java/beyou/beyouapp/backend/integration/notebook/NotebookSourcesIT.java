package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.NotebookRetriever;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSource;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceRepository;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceStatus;
import beyou.beyouapp.backend.domain.notebook.source.SourceChunkStore;
import beyou.beyouapp.backend.domain.notebook.source.SourceIngestionService;
import beyou.beyouapp.backend.domain.notebook.source.dto.AddLinkRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.AddTextRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.SourceDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Sources against a real Postgres: the chunks, the generated tsvector the search runs on, and the
 * scope a page reads.
 *
 * <p>Deliberately NOT {@code @Transactional}. Ingestion writes its status in transactions of its
 * own (it runs on a pool thread in production), and those cannot see a row the test's
 * transaction never committed. Every test makes its own user, so leftovers do not collide.
 * Embeddings are off in the test profile, so retrieval here is the full-text path.
 */
class NotebookSourcesIT extends AbstractIntegrationTest {

    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private NotebookSourceService sourceService;
    @Autowired private NotebookSourceRepository sourceRepository;
    @Autowired private SourceIngestionService ingestionService;
    @Autowired private SourceChunkStore chunkStore;
    @Autowired private NotebookRetriever retriever;
    @Autowired private UserRepository userRepository;

    private User user;
    private UUID topicId;

    @BeforeEach
    void seed() {
        user = newUser("sources");
        topicId = pageService.createTopic(user,
                new CreateTopicRequestDTO("Data Structures", null, null, null, null, null)).id();
    }

    @Test
    void pastedTextIsReadIntoSearchableChunks() {
        SourceDTO added = sourceService.addText(user, topicId, new AddTextRequestDTO("Lecture 6",
                "Binary search trees keep keys in order.\n\nTo delete a node with two children, copy the "
                        + "in-order successor up and delete the successor instead."));
        assertThat(added.status()).isEqualTo(NotebookSourceStatus.PENDING);

        ingestionService.ingest(added.id(), user.getId(),
                new SourceIngestionService.TextPayload(textOf(added)));

        NotebookSource read = sourceRepository.findById(added.id()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(NotebookSourceStatus.READY);
        assertThat(read.getProgress()).isEqualTo(100);
        List<SourceChunkStore.StoredChunk> found = retriever.retrieve(List.of(added.id()), "how is the successor deleted", 3);
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).content()).contains("successor");
    }

    @Test
    void aPdfIsReadPageByPageAndKeepsItsPageNumbers() throws Exception {
        byte[] pdf = pdf("Arrays store items next to each other.", "Hash tables trade memory for speed.");
        SourceDTO added = sourceService.addPdf(user, topicId,
                new MockMultipartFile("file", "../../etc/notes.pdf", "application/pdf", pdf));
        // The path a crafted upload sends never becomes part of the title.
        assertThat(added.title()).isEqualTo("notes.pdf");

        ingestionService.ingest(added.id(), user.getId(), new SourceIngestionService.PdfPayload(pdf));

        NotebookSource read = sourceRepository.findById(added.id()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(NotebookSourceStatus.READY);
        assertThat(read.getPageCount()).isEqualTo(2);
        List<SourceChunkStore.StoredChunk> hashes = retriever.retrieve(List.of(added.id()), "hash tables memory", 1);
        assertThat(hashes.get(0).pageNumber()).isEqualTo(2);
    }

    /** The magic number decides, not the content type the browser claimed. */
    @Test
    void aFileThatIsNotAPdfIsRefused() {
        assertThatThrownBy(() -> sourceService.addPdf(user, topicId,
                new MockMultipartFile("file", "virus.pdf", "application/pdf", "MZ not a pdf".getBytes())))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE);
    }

    /** The server must never be a proxy into its own network. Refused before a row exists. */
    @Test
    void aLinkIntoThePrivateNetworkIsRefusedAndNotStored() {
        long before = sourceRepository.countByPageId(topicId);

        assertThatThrownBy(() -> sourceService.addLink(user, topicId,
                new AddLinkRequestDTO("http://127.0.0.1:9091/actuator/prometheus")))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_SOURCE_URL_REFUSED);
        assertThat(sourceRepository.countByPageId(topicId)).isEqualTo(before);
    }

    /** A textbook on the topic answers on every node; a paper on one node stays on that node. */
    @Test
    void aPageReadsItsOwnAndItsAncestorsSourcesButNotItsSiblings() {
        UUID trees = node(topicId, "Trees");
        UUID heaps = node(topicId, "Heaps");
        SourceDTO book = ready(topicId, "Textbook", "Trees and heaps are both covered here.");
        SourceDTO paper = ready(trees, "AVL paper", "Rotations keep the tree balanced.");

        assertThat(sourceService.list(user, trees)).extracting(SourceDTO::id).containsExactlyInAnyOrder(book.id(), paper.id());
        assertThat(sourceService.list(user, trees)).filteredOn(s -> s.id().equals(book.id()))
                .singleElement().satisfies(s -> assertThat(s.inherited()).isTrue());
        assertThat(sourceService.list(user, heaps)).extracting(SourceDTO::id).containsExactly(book.id());
    }

    @Test
    void aSwitchedOffSourceIsNotReadByAnswers() {
        SourceDTO book = ready(topicId, "Textbook", "Some text to read.");

        sourceService.setEnabled(user, book.id(), false);

        assertThat(sourceService.readable(user, topicId)).isEmpty();
        assertThat(sourceService.list(user, topicId)).hasSize(1);
    }

    @Test
    void aStrangerCannotReadSomebodysSources() {
        SourceDTO book = ready(topicId, "Textbook", "Private notes.");
        User stranger = newUser("sources-stranger");

        assertThatThrownBy(() -> sourceService.list(stranger, topicId))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED);
        assertThatThrownBy(() -> sourceService.delete(stranger, book.id()))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED);
    }

    /** A PDF's bytes die with the process, so a restart cannot resume it; it is failed, honestly. */
    @Test
    void aRestartFailsWhatWasHalfRead() {
        SourceDTO pending = sourceService.addText(user, topicId, new AddTextRequestDTO("Half read", "Never finished."));

        ingestionService.failInterrupted();

        NotebookSource read = sourceRepository.findById(pending.id()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(NotebookSourceStatus.FAILED);
        assertThat(read.getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_SOURCE_INTERRUPTED.name());
    }

    @Test
    void aPassageComesWithItsNeighbours() {
        String text = String.join("\n\n", "First part. " + "x ".repeat(450), "Second part. " + "y ".repeat(450),
                "Third part. " + "z ".repeat(450));
        SourceDTO source = ready(topicId, "Long", text);
        List<SourceChunkStore.StoredChunk> chunks = chunkStore.forSource(source.id());
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);

        var passage = sourceService.passage(user, source.id(), chunks.get(1).id());

        assertThat(passage.text()).startsWith("Second part.");
        assertThat(passage.before()).startsWith("First part.");
        assertThat(passage.after()).startsWith("Third part.");
    }

    // ---------------------------------------------------------------- helpers

    private SourceDTO ready(UUID pageId, String title, String text) {
        SourceDTO added = sourceService.addText(user, pageId, new AddTextRequestDTO(title, text));
        ingestionService.ingest(added.id(), user.getId(), new SourceIngestionService.TextPayload(text));
        return added;
    }

    private String textOf(SourceDTO ignored) {
        return "Binary search trees keep keys in order.\n\nTo delete a node with two children, copy the "
                + "in-order successor up and delete the successor instead.";
    }

    private UUID node(UUID boardPageId, String title) {
        return boardService.addNode(user, boardPageId,
                new CreateNodeRequestDTO(null, title, null, null, 0.0, 0.0, null, null)).node().pageId();
    }

    private static byte[] pdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
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
