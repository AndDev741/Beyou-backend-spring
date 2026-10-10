package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardService;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/**
 * HTTP binding of the four notebook controllers NotebookControllerTest does not cover: board,
 * cards, sources and the study room. Thin on purpose. The rules live in the services and their
 * ITs; what can only break here is a body that reaches a service without passing validation, or
 * a refusal that reaches the client without its error key. One class, so one Spring context.
 */
@AutoConfigureMockMvc(addFilters = false)
class NotebookStudyControllersTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private NotebookBoardService boardService;
    @MockitoBean private NotebookCardService cardService;
    @MockitoBean private NotebookSourceService sourceService;
    @MockitoBean private NotebookStudyService studyService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    // ------------------------------------------------------------------ board

    @Test
    void anEdgeWithoutBothEndsIsRefusedBeforeTheService() throws Exception {
        send(post("/notebook/pages/" + id + "/board/edges"), "{\"source\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        verifyNoInteractions(boardService);
    }

    @Test
    void deletingAnEdgeAnswers204() throws Exception {
        mockMvc.perform(delete("/notebook/board/edges/" + id)).andExpect(status().isNoContent());
        verify(boardService).deleteEdge(user, id);
    }

    @Test
    void anEdgeThatIsGoneComesBackWithItsKey() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorKey.NOTEBOOK_EDGE_NOT_FOUND, "Edge not found"))
                .when(boardService).deleteEdge(user, id);

        mockMvc.perform(delete("/notebook/board/edges/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("NOTEBOOK_EDGE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ cards

    @Test
    void aCardWithABlankFrontIsRefusedBeforeTheService() throws Exception {
        send(post("/notebook/pages/" + id + "/cards"), "{\"front\":\"  \",\"back\":\"An answer\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cardService);
    }

    @Test
    void aReviewWithoutARatingIsRefusedBeforeTheService() throws Exception {
        send(post("/notebook/cards/" + id + "/review"), "{}").andExpect(status().isBadRequest());
        verifyNoInteractions(cardService);
    }

    @Test
    void editingACardThatIsGoneComesBackWithItsKey() throws Exception {
        when(cardService.update(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorKey.NOTEBOOK_CARD_NOT_FOUND, "Card not found"));

        send(patch("/notebook/cards/" + id), "{\"front\":\"New question?\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("NOTEBOOK_CARD_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- sources

    @Test
    void aLinkOverTheLengthCapIsRefusedBeforeTheService() throws Exception {
        String url = "https://example.test/" + "a".repeat(2048);
        send(post("/notebook/pages/" + id + "/sources/link"), "{\"url\":\"" + url + "\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sourceService);
    }

    @Test
    void pastedTextWithNoTitleIsRefusedBeforeTheService() throws Exception {
        send(post("/notebook/pages/" + id + "/sources/text"), "{\"title\":\"\",\"text\":\"Some notes\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sourceService);
    }

    @Test
    void aPassageOfAMissingSourceComesBackWithItsKey() throws Exception {
        UUID chunk = UUID.randomUUID();
        when(sourceService.passage(user, id, chunk))
                .thenThrow(new BusinessException(ErrorKey.NOTEBOOK_SOURCE_NOT_FOUND, "Source not found"));

        mockMvc.perform(get("/notebook/sources/" + id + "/passages/" + chunk))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("NOTEBOOK_SOURCE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ study

    @Test
    void aSetupWithoutAScopeIsRefusedBeforeTheService() throws Exception {
        send(put("/notebook/pages/" + id + "/study/setup"), "{\"goal\":\"Pass the exam\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(studyService);
    }

    @Test
    void aQuizAnswerListOverTheCapIsRefusedBeforeTheService() throws Exception {
        String answers = "[" + "0,".repeat(30) + "0]";
        send(post("/notebook/outputs/" + id + "/quiz-result"), "{\"answers\":" + answers + "}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(studyService);
    }

    @Test
    void gradingAnOutputThatIsGoneComesBackWithItsKey() throws Exception {
        when(studyService.grade(any(), any(), anyList()))
                .thenThrow(new BusinessException(ErrorKey.NOTEBOOK_OUTPUT_NOT_FOUND, "Output not found"));

        send(post("/notebook/outputs/" + id + "/quiz-result"), "{\"answers\":[0,1,2]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("NOTEBOOK_OUTPUT_NOT_FOUND"));
    }

    @Test
    void clearingTheChatAnswers204() throws Exception {
        mockMvc.perform(delete("/notebook/pages/" + id + "/study/messages")).andExpect(status().isNoContent());
        verify(studyService).clearChat(user, id);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
