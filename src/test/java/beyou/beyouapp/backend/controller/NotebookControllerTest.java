package beyou.beyouapp.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftService;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramKind;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramLink;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramNode;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramService;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramTone;
import beyou.beyouapp.backend.domain.notebook.diagram.dto.DiagramRequestDTO;
import beyou.beyouapp.backend.domain.notebook.diagram.dto.DiagramResultDTO;
import beyou.beyouapp.backend.domain.notebook.dto.AppendRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.HomeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.ReviewSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChoice;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/** HTTP binding of the notebook routes. The rules themselves live in the notebook ITs. */
@AutoConfigureMockMvc(addFilters = false)
class NotebookControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private NotebookPageService pageService;
    @MockitoBean private NotebookAiService aiService;
    @MockitoBean private RoadmapDraftService draftService;
    @MockitoBean private DiagramService diagramService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void theHomeAnswersInOneCall() throws Exception {
        when(pageService.home(user)).thenReturn(new HomeResponseDTO(List.of(), null, new ReviewSummaryDTO(3, List.of(), 2)));

        mockMvc.perform(get("/notebook/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.due").value(3))
                .andExpect(jsonPath("$.review.streak").value(2));
    }

    @Test
    void aTopicNeedsATitle() throws Exception {
        mockMvc.perform(post("/notebook/topics").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        verify(pageService, never()).createTopic(any(), any());
    }

    /** AUTO is a choice the API accepts even though it is not a status. */
    @Test
    void autoReachesTheServiceAsAChoice() throws Exception {
        UUID page = UUID.randomUUID();
        when(pageService.setStatus(eq(user), eq(page), eq(new SetStatusRequestDTO(StatusChoice.AUTO))))
                .thenReturn(new StatusChangeResponseDTO(page, NotebookStatus.STUDYING, false, List.of(), 0, null));

        mockMvc.perform(put("/notebook/pages/" + page + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"AUTO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STUDYING"));
    }

    @Test
    void aDraftRefusesAnImpossibleWeek() throws Exception {
        mockMvc.perform(post("/notebook/ai/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"CS\",\"hoursPerWeek\":0}"))
                .andExpect(status().isBadRequest());
        verify(draftService, never()).start(any(), any());
    }

    // --------------------------------------------------------------- diagrams

    @Test
    void aDiagramNeedsAPromptOfAtMost1000Characters() throws Exception {
        for (String body : List.of("{\"prompt\":\"  \"}", "{\"prompt\":\"" + "a".repeat(1001) + "\"}")) {
            mockMvc.perform(post("/notebook/ai/pages/" + UUID.randomUUID() + "/diagram")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        }
        verify(diagramService, never()).generate(any(), any(), any());
    }

    /** The wire shape the clients read: the field names of contract section 1, nulls included. */
    @Test
    void aDiagramAnswersWithTheSpecAndWhereItCameFrom() throws Exception {
        UUID page = UUID.randomUUID();
        DiagramSpec spec = new DiagramSpec(1, DiagramKind.FLOW, "Login", List.of(
                new DiagramNode("n1", "Password", null, DiagramTone.ACCENT),
                new DiagramNode("n2", "Session", "15 minutes", DiagramTone.SUCCESS)),
                List.of(new DiagramLink("n1", "n2", "match", false)));
        when(diagramService.generate(eq(user), eq(page), any())).thenReturn(new DiagramResultDTO(spec, true));

        mockMvc.perform(post("/notebook/ai/pages/" + page + "/diagram").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"How login works\",\"kind\":\"flow\",\"grounded\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromNotes").value(true))
                .andExpect(jsonPath("$.diagram.v").value(1))
                .andExpect(jsonPath("$.diagram.kind").value("FLOW"))
                .andExpect(jsonPath("$.diagram.nodes[0].id").value("n1"))
                .andExpect(jsonPath("$.diagram.nodes[0].tone").value("ACCENT"))
                .andExpect(jsonPath("$.diagram.nodes[0].detail").isEmpty())
                .andExpect(jsonPath("$.diagram.links[0].from").value("n1"))
                .andExpect(jsonPath("$.diagram.links[0].dashed").value(false));

        ArgumentCaptor<DiagramRequestDTO> request = ArgumentCaptor.forClass(DiagramRequestDTO.class);
        verify(diagramService).generate(eq(user), eq(page), request.capture());
        assertThat(request.getValue().kind()).isEqualTo(DiagramKind.FLOW);
        assertThat(request.getValue().grounded()).isFalse();
    }

    @Test
    void anAppendCarriesADiagramWithoutMarkdownButNotMarkdownOverTheCap() throws Exception {
        UUID page = UUID.randomUUID();
        mockMvc.perform(post("/notebook/pages/" + page + "/append").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diagram\":{\"kind\":\"MAP\",\"nodes\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}],"
                                + "\"links\":[{\"from\":\"a\",\"to\":\"b\"}]}}"))
                .andExpect(status().isOk());
        ArgumentCaptor<AppendRequestDTO> request = ArgumentCaptor.forClass(AppendRequestDTO.class);
        verify(pageService).append(eq(user), eq(page), request.capture());
        assertThat(request.getValue().markdown()).isNull();
        assertThat(request.getValue().diagram().nodes()).hasSize(2);

        mockMvc.perform(post("/notebook/pages/" + page + "/append").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"" + "a".repeat(20_001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }
}
