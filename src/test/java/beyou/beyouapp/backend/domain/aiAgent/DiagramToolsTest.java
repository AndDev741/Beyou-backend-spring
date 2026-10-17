package beyou.beyouapp.backend.domain.aiAgent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import beyou.beyouapp.backend.domain.aiAgent.dto.AgentEvent;
import beyou.beyouapp.backend.domain.aiAgent.notebook.StudyDiagramEditor;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramKind;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramNode;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramTone;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The diagram tools the way a turn runs them: Spring AI's MethodToolCallback fed the raw JSON a
 * model sends, wrapped in the MeteredToolCallback the agent uses, its events fed to the
 * AgentTurnBuilder that persists the turn. What this locks in is the whole road a diagram takes:
 * the nested record really binds from JSON (a typed unit call could never show that), it is
 * cleaned, and the clean spec reaches the finished event and the stored segment, while the
 * segments of every other tool keep their old shape.
 */
@ExtendWith(MockitoExtension.class)
class DiagramToolsTest {

    @Mock private UserService userService;
    @Mock private StudyDiagramEditor mockedEditor;
    @InjectMocks private Tools tools;

    private final UUID userId = UUID.randomUUID();
    private final List<AgentEvent> events = new ArrayList<>();
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userId);
        context.put(MeteredToolCallback.EVENTS_KEY, (Consumer<AgentEvent>) events::add);
        toolContext = new ToolContext(context);
    }

    private ToolCallback metered(String name) {
        ToolCallback callback = Arrays.stream(
                        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no tool named " + name));
        return new MeteredToolCallback(callback, new SimpleMeterRegistry());
    }

    private void realEditor() {
        // show() only cleans, so the page services it never touches can be absent.
        ReflectionTestUtils.setField(tools, "studyDiagramEditor", new StudyDiagramEditor(null, null));
    }

    private Map<String, Object> finished() {
        return events.stream()
                .filter(e -> "finished".equals(e.data().get("status")))
                .findFirst().orElseThrow().data();
    }

    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) sb.append(cur.getMessage()).append(" | ");
        return sb.toString();
    }

    @Test
    void showDiagramBindsTheNestedSpecCleansItAndPutsItOnTheEventAndTheSegment() {
        realEditor();
        // Lowercase kind and tone, a node with no id, a link to nothing and a field nobody asked for:
        // what free-tier models send.
        String json = """
                {"diagram": {"kind": "cycle", "title": "Water cycle", "colour": "blue", "nodes": [
                  {"id": "a", "label": "Evaporation", "tone": "accent"},
                  {"label": "Condensation"},
                  {"id": "c", "label": "Rain", "detail": "falls back"}],
                 "links": [{"from": "a", "to": "ghost", "label": "never"}]}}
                """;

        String result = metered("showDiagram").call(json, toolContext);

        assertThat(result).contains("Water cycle").contains("on the user's screen");
        DiagramSpec diagram = (DiagramSpec) finished().get("diagram");
        assertThat(diagram.kind()).isEqualTo(DiagramKind.CYCLE);
        assertThat(diagram.nodes()).extracting(DiagramNode::id).containsExactly("a", "n2", "c");
        assertThat(diagram.nodes().get(0).tone()).isEqualTo(DiagramTone.ACCENT);
        assertThat(diagram.links()).isEmpty();
        assertThat(finished()).doesNotContainKeys("pageId", "pageTitle", "error");
        assertThat(finished().get("domains")).isEqualTo(List.of());

        AgentTurnBuilder turn = new AgentTurnBuilder();
        events.forEach(turn::observe);
        AgentSegment segment = turn.build().get(0);
        assertThat(segment.tool()).isEqualTo("showDiagram");
        assertThat(segment.diagram()).isEqualTo(diagram);
    }

    /** A spec with nothing to draw comes back to the model as a message saying what to send, and no diagram rides along. */
    @Test
    void showDiagramWithOneNodeTellsTheModelWhatToSend() {
        realEditor();
        String json = """
                {"diagram": {"kind": "FLOW", "nodes": [{"id": "a", "label": "Alone"}, {"id": "b", "label": "  "}]}}
                """;

        assertThatThrownBy(() -> metered("showDiagram").call(json, toolContext))
                .satisfies(e -> assertThat(messageChain(e)).contains("at least two nodes with a label"));
        assertThat(finished()).containsKey("error").doesNotContainKey("diagram");
    }

    @Test
    void addStudyDiagramBindsThePageAndTheSpecAndReportsWhereItWent() {
        User user = new User();
        user.setId(userId);
        when(userService.findUserById(userId)).thenReturn(user);
        UUID pageId = UUID.randomUUID();
        DiagramSpec clean = new DiagramSpec(1, DiagramKind.MAP, "Cell", List.of(
                new DiagramNode("n1", "Cell", null, DiagramTone.ACCENT),
                new DiagramNode("n2", "Nucleus", null, DiagramTone.NEUTRAL)), List.of());
        when(mockedEditor.add(eq(user), eq("Biology"), any())).thenReturn(new StudyDiagramEditor.Added(clean, pageId, "Biology"));
        String json = """
                {"page": "Biology", "diagram": {"kind": "MAP", "title": "Cell",
                 "nodes": [{"id": "n1", "label": "Cell"}, {"id": "n2", "label": "Nucleus"}],
                 "links": [{"from": "n1", "to": "n2", "dashed": true}]}}
                """;

        String result = metered("addStudyDiagram").call(json, toolContext);

        ArgumentCaptor<DiagramSpec> sent = ArgumentCaptor.forClass(DiagramSpec.class);
        verify(mockedEditor).add(eq(user), eq("Biology"), sent.capture());
        assertThat(sent.getValue().kind()).isEqualTo(DiagramKind.MAP);
        assertThat(sent.getValue().nodes()).extracting(DiagramNode::label).containsExactly("Cell", "Nucleus");
        assertThat(sent.getValue().links().get(0).dashed()).isTrue();
        assertThat(result).contains("Added the diagram \\\"Cell\\\" at the end of \\\"Biology\\\"");
        assertThat(finished()).containsEntry("diagram", clean).containsEntry("pageId", pageId)
                .containsEntry("pageTitle", "Biology").containsEntry("domains", List.of("notebook"));

        AgentTurnBuilder turn = new AgentTurnBuilder();
        events.forEach(turn::observe);
        AgentSegment segment = turn.build().get(0);
        assertThat(segment.pageId()).isEqualTo(pageId);
        assertThat(segment.pageTitle()).isEqualTo("Biology");
    }

    /**
     * The stored transcript: a diagram segment survives the JSON round trip agent_message makes,
     * and every other segment is written exactly as before, with no new keys.
     */
    @Test
    void segmentsRoundTripAndOldShapesStayUnchanged() {
        JsonMapper mapper = JsonMapper.builder().build();
        DiagramSpec diagram = new DiagramSpec(1, DiagramKind.FLOW, "t", List.of(
                new DiagramNode("n1", "A", null, DiagramTone.NEUTRAL),
                new DiagramNode("n2", "B", "b", DiagramTone.SUCCESS)), List.of());
        UUID pageId = UUID.randomUUID();
        List<AgentSegment> segments = List.of(
                AgentSegment.text("hi"),
                AgentSegment.tool("getUserHabits", null, List.of()),
                AgentSegment.tool("addStudyDiagram", null, List.of("notebook"), diagram, pageId, "Page"));

        String json = mapper.writeValueAsString(segments);
        List<AgentSegment> back = mapper.readValue(json, new TypeReference<List<AgentSegment>>() { });

        assertThat(back).isEqualTo(segments);
        assertThat(mapper.writeValueAsString(segments.get(1)))
                .isEqualTo("{\"type\":\"tool\",\"tool\":\"getUserHabits\",\"domains\":[]}");
        assertThat(json).contains("\"pageTitle\":\"Page\"").contains("\"pageId\":\"" + pageId + "\"");
    }
}
