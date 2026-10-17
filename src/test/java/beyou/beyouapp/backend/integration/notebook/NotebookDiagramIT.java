package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.notebook.StudyDiagramEditor;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.MarkdownBlocks;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookLlm;
import beyou.beyouapp.backend.domain.notebook.ai.dto.LlmPayloads;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramKind;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramLink;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramNode;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramService;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramTone;
import beyou.beyouapp.backend.domain.notebook.diagram.dto.DiagramRequestDTO;
import beyou.beyouapp.backend.domain.notebook.diagram.dto.DiagramResultDTO;
import beyou.beyouapp.backend.domain.notebook.dto.AppendRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.domain.notebook.study.dto.ChatMessageDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.ChatTurnDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Diagrams against a real Postgres, with the model mocked: appending one to a page, the study
 * chat drawing one (stored in V41's column), the generate endpoint's service, and the assistant's
 * tool. What is under test is what the server keeps of a diagram and where it puts it.
 */
@Transactional
class NotebookDiagramIT extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @MockitoBean private NotebookLlm llm;

    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private NotebookStudyService studyService;
    @Autowired private DiagramService diagramService;
    @Autowired private StudyDiagramEditor diagramEditor;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private UUID topic;
    private UUID trees;

    @BeforeEach
    void seed() {
        user = newUser();
        topic = pageService.createTopic(user, new CreateTopicRequestDTO("Data Structures", null, null, null, null, null)).id();
        trees = boardService.addNode(user, topic,
                new CreateNodeRequestDTO(null, "Trees", null, null, 0.0, 0.0, null, null, null)).node().pageId();
    }

    /** A raw spec as a model or a client might send it: a dangling link, a blank node, a duplicate link. */
    private static DiagramSpec messy() {
        return new DiagramSpec(null, DiagramKind.FLOW, "Delete a node", List.of(
                new DiagramNode("a", "Find the node", null, DiagramTone.ACCENT),
                new DiagramNode("b", "  ", "no label, dropped", null),
                new DiagramNode("c", "Two children?", null, null),
                new DiagramNode("d", "Use the successor", "smallest on the right", DiagramTone.SUCCESS)),
                List.of(new DiagramLink("a", "c", null, null),
                        new DiagramLink("a", "b", "to a dropped node", null),
                        new DiagramLink("c", "ghost", "to nothing", null),
                        new DiagramLink("c", "d", "yes", null),
                        new DiagramLink("c", "d", "yes", true)));
    }

    // ------------------------------------------------------------------ append

    @Test
    void appendingADiagramPutsOneCleanBlockAfterTheMarkdownAndItsTextInThePageText() {
        pageService.saveContent(user, trees, new UpdateContentRequestDTO(paragraph("Mine"), null));

        pageService.append(user, trees, new AppendRequestDTO("Saved from the chat", messy()));

        JsonNode blocks = JSON.readTree(content(trees));
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1).path("type").asString()).isEqualTo("paragraph");
        JsonNode block = blocks.get(2);
        assertThat(block.path("type").asString()).isEqualTo(MarkdownBlocks.DIAGRAM_BLOCK_TYPE);
        DiagramSpec stored = JSON.readValue(block.path("props").path("spec").asString(), DiagramSpec.class);
        assertThat(stored.v()).isEqualTo(1);
        assertThat(stored.nodes()).extracting(DiagramNode::id).containsExactly("a", "c", "d");
        assertThat(stored.links()).extracting(l -> l.from() + ">" + l.to() + ":" + l.label())
                .containsExactly("a>c:null", "c>d:yes");
        assertThat(contentText(trees)).isEqualTo(
                "Mine\nSaved from the chat\nDelete a node\nFind the node\nTwo children?\n"
                        + "Use the successor: smallest on the right\nyes");
    }

    @Test
    void aDiagramAloneIsEnough() {
        pageService.append(user, trees, new AppendRequestDTO(null, messy()));

        assertThat(JSON.readTree(content(trees))).singleElement()
                .satisfies(b -> assertThat(b.path("type").asString()).isEqualTo("diagram"));
    }

    @Test
    void anAppendWithNothingOrADiagramWithNothingToDrawIsRefusedAndChangesNothing() {
        pageService.saveContent(user, trees, new UpdateContentRequestDTO(paragraph("Mine"), null));
        String before = content(trees);
        DiagramSpec oneNode = new DiagramSpec(1, DiagramKind.FLOW, "Alone",
                List.of(new DiagramNode("a", "Only", null, null), new DiagramNode("b", " ", null, null)), List.of());

        for (AppendRequestDTO request : List.of(new AppendRequestDTO(null, null), new AppendRequestDTO("   ", null),
                new AppendRequestDTO("Text too", oneNode))) {
            assertThatThrownBy(() -> pageService.append(user, trees, request))
                    .extracting(e -> ((BusinessException) e).getErrorKey())
                    .isEqualTo(ErrorKey.INVALID_REQUEST);
        }
        assertThat(content(trees)).isEqualTo(before);
    }

    // -------------------------------------------------------------------- chat

    @Test
    void aChatAnswerKeepsItsCleanedDiagramAndTheRoomReadsItBack() {
        when(llm.call(eq(LlmPayloads.ChatPayload.class), anyString(), any()))
                .thenReturn(new LlmPayloads.ChatPayload("Here is how deletion goes.", List.of(), messy()));

        ChatTurnDTO turn = studyService.chat(user, trees, "Draw how deleting a node works");

        assertThat(turn.question().diagram()).isNull();
        assertThat(turn.answer().diagram().nodes()).extracting(DiagramNode::id).containsExactly("a", "c", "d");
        List<ChatMessageDTO> history = studyService.study(user, trees).messages();
        assertThat(history.get(1).diagram()).isEqualTo(turn.answer().diagram());
        assertThat(history.get(0).diagram()).isNull();
        // V41's column holds the cleaned spec as JSON.
        String column = jdbc.queryForObject(
                "SELECT diagram FROM notebook_chat_messages WHERE page_id = ? AND role = 'ASSISTANT'", String.class, trees);
        assertThat(JSON.readValue(column, DiagramSpec.class)).isEqualTo(turn.answer().diagram());
    }

    /** The words are what the person asked for; a picture with nothing to draw is dropped, not an error. */
    @Test
    void aChatDiagramWithNothingToDrawIsDroppedAndTheAnswerKept() {
        DiagramSpec oneNode = new DiagramSpec(1, DiagramKind.MAP, "x",
                List.of(new DiagramNode("a", "Only", null, null)), List.of());
        when(llm.call(eq(LlmPayloads.ChatPayload.class), anyString(), any()))
                .thenReturn(new LlmPayloads.ChatPayload("An answer.", List.of(), oneNode));

        ChatTurnDTO turn = studyService.chat(user, trees, "What is a tree?");

        assertThat(turn.answer().content()).isEqualTo("An answer.");
        assertThat(turn.answer().diagram()).isNull();
    }

    /** The chat asks for its diagram field and sends the earlier diagram back, so "change that diagram" works. */
    @Test
    void theNextQuestionCarriesTheEarlierDiagram() {
        when(llm.call(eq(LlmPayloads.ChatPayload.class), anyString(), any()))
                .thenReturn(new LlmPayloads.ChatPayload("Drawn.", List.of(), messy()))
                .thenReturn(new LlmPayloads.ChatPayload("Changed.", List.of(), null));

        studyService.chat(user, trees, "Draw deletion");
        studyService.chat(user, trees, "Add the leaf case to that diagram");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).call(eq(LlmPayloads.ChatPayload.class), prompts.capture(), any());
        assertThat(prompts.getAllValues().get(0)).contains("\"diagram\":null").contains("Drawing a diagram");
        assertThat(prompts.getAllValues().get(1))
                .contains("(The tutor drew this diagram with that answer: {\"v\":1,\"kind\":\"FLOW\",\"title\":\"Delete a node\"");
    }

    // ---------------------------------------------------------------- generate

    @Test
    void aGroundedDiagramReadsThePageAndSaysItCameFromTheNotes() {
        pageService.saveContent(user, trees, new UpdateContentRequestDTO(
                paragraph("To delete a node with two children use the in-order successor."), null));
        when(llm.call(eq(DiagramSpec.class), anyString(), any())).thenReturn(messy());

        DiagramResultDTO result = diagramService.generate(user, trees,
                new DiagramRequestDTO("How deletion works", null, null, null));

        assertThat(result.fromNotes()).isTrue();
        assertThat(result.diagram().nodes()).hasSize(3);
        String prompt = diagramPrompt();
        assertThat(prompt).startsWith("DIAGRAM\n")
                .contains("The person is studying the page \"Trees\", in the topic \"Data Structures\".")
                .contains("use the in-order successor")
                .contains("Pick the kind that fits the request best.")
                .contains("The person asks for: \"How deletion works\"")
                .contains("\"kind\":\"MAP\"");
    }

    @Test
    void anUngroundedDiagramOrAnEmptyPageDrawsFromTheModelsKnowledge() {
        pageService.saveContent(user, trees, new UpdateContentRequestDTO(paragraph("A heap keeps the smallest key at the root."), null));
        when(llm.call(eq(DiagramSpec.class), anyString(), any())).thenReturn(messy());

        DiagramResultDTO ungrounded = diagramService.generate(user, trees,
                new DiagramRequestDTO("The OSI layers", null, false, null));
        UUID empty = boardService.addNode(user, topic,
                new CreateNodeRequestDTO(null, "Graphs", null, null, 0.0, 200.0, null, null, null)).node().pageId();
        DiagramResultDTO emptyPage = diagramService.generate(user, empty,
                new DiagramRequestDTO("BFS", null, true, null));

        assertThat(ungrounded.fromNotes()).isFalse();
        assertThat(emptyPage.fromNotes()).isFalse();
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).call(eq(DiagramSpec.class), prompts.capture(), any());
        assertThat(prompts.getAllValues().get(0)).doesNotContain("smallest key").contains("from your own knowledge");
        assertThat(prompts.getAllValues().get(1)).contains("this page has no notes and no sources yet");
    }

    /** A kind the person chose is what they get, whatever the model answered; the caps follow that kind. */
    @Test
    void aChosenKindIsEnforced() {
        List<DiagramNode> eight = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) eight.add(new DiagramNode("n" + i, "Step " + i, null, null));
        when(llm.call(eq(DiagramSpec.class), anyString(), any()))
                .thenReturn(new DiagramSpec(1, DiagramKind.FLOW, "Steps", eight, List.of()));

        DiagramResultDTO result = diagramService.generate(user, trees,
                new DiagramRequestDTO("The client and server", DiagramKind.SEQUENCE, false, null));

        assertThat(result.diagram().kind()).isEqualTo(DiagramKind.SEQUENCE);
        assertThat(result.diagram().nodes()).hasSize(6);
        assertThat(diagramPrompt()).contains("Draw it as a SEQUENCE diagram.");
    }

    @Test
    void anAdjustmentSendsTheCurrentDiagramAndAsksToKeepTheRest() {
        when(llm.call(eq(DiagramSpec.class), anyString(), any())).thenReturn(messy());

        diagramService.generate(user, trees, new DiagramRequestDTO("Add the leaf case", null, false, messy()));

        assertThat(diagramPrompt())
                .contains("The diagram the person has now:\n{\"v\":1,\"kind\":\"FLOW\",\"title\":\"Delete a node\"")
                .contains("The person asks for this change: \"Add the leaf case\"")
                .contains("Keep everything the change does not touch")
                // The base was cleaned before it went in: the dropped node is not in the prompt.
                .doesNotContain("no label, dropped");
    }

    @Test
    void aBaseWithNothingToDrawIsRefusedBeforeTheModel() {
        DiagramSpec broken = new DiagramSpec(1, DiagramKind.FLOW, "x", List.of(), List.of());

        assertThatThrownBy(() -> diagramService.generate(user, trees, new DiagramRequestDTO("Change it", null, true, broken)))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.INVALID_REQUEST);
        verify(llm, never()).call(any(), anyString(), any());
    }

    @Test
    void aModelDiagramWithNothingToDrawIsAiUnavailable() {
        when(llm.call(eq(DiagramSpec.class), anyString(), any())).thenReturn(
                new DiagramSpec(1, DiagramKind.FLOW, "x", List.of(new DiagramNode("a", "A", null, null)), List.of()));

        assertThatThrownBy(() -> diagramService.generate(user, trees, new DiagramRequestDTO("Draw", null, true, null)))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.AI_UNAVAILABLE);
    }

    // --------------------------------------------------------------- assistant

    /** The assistant's tool finds the page the way the board tools do and appends through the same path. */
    @Test
    void theAssistantAddsACleanDiagramToAPageByItsTitle() {
        StudyDiagramEditor.Added added = diagramEditor.add(user, "trees", messy());

        assertThat(added.pageId()).isEqualTo(trees);
        assertThat(added.pageTitle()).isEqualTo("Trees");
        assertThat(added.diagram().nodes()).hasSize(3);
        assertThat(contentText(trees)).contains("Use the successor: smallest on the right");
    }

    @Test
    void theAssistantIsRefusedAnAmbiguousPageAndADiagramWithNothingToDraw() {
        UUID other = pageService.createTopic(user, new CreateTopicRequestDTO("Botany", null, null, null, null, null)).id();
        boardService.addNode(user, other, new CreateNodeRequestDTO(null, "Trees", null, null, 0.0, 0.0, null, null, null));
        DiagramSpec oneNode = new DiagramSpec(1, DiagramKind.FLOW, "x",
                List.of(new DiagramNode("a", "A", null, null)), List.of());

        assertThatThrownBy(() -> diagramEditor.add(user, "Trees", messy()))
                .hasMessageContaining("Several pages are titled \"Trees\"");
        assertThatThrownBy(() -> diagramEditor.add(user, trees.toString(), oneNode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two nodes with a label");
        assertThatThrownBy(() -> diagramEditor.add(user, " ", messy()))
                .hasMessageContaining("Say which page gets the diagram");
    }

    // ----------------------------------------------------------------- helpers

    private String diagramPrompt() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).call(eq(DiagramSpec.class), prompt.capture(), any());
        return prompt.getValue();
    }

    private String content(UUID pageId) {
        return jdbc.queryForObject("SELECT content FROM notebook_pages WHERE id = ?", String.class, pageId);
    }

    private String contentText(UUID pageId) {
        return jdbc.queryForObject("SELECT content_text FROM notebook_pages WHERE id = ?", String.class, pageId);
    }

    private static String paragraph(String text) {
        return "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\",\"styles\":{}}]}]";
    }

    private User newUser() {
        User u = new User();
        u.setName("notebook-diagram");
        u.setEmail("notebook-diagram-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
