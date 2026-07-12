package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.AiAgentService;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.transaction.Transactional;

@ExtendWith(MockitoExtension.class)
@Transactional
@AutoConfigureMockMvc(addFilters = false)
public class AiAgentControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiAgentService agentService;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private AuthenticatedUser authenticatedUser;

    private UUID userId;
    private UUID chatId;
    private ChatResponseDTO chatDTO;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        chatDTO = new ChatResponseDTO(chatId, "My chat", LocalDateTime.now(), LocalDateTime.now());

        User user = new User();
        user.setId(userId);
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void shouldListChats() throws Exception {
        when(chatService.getAllChats(userId)).thenReturn(List.of(chatDTO));

        mockMvc.perform(get("/ai/agent/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(chatId.toString()))
                .andExpect(jsonPath("$[0].title").value("My chat"));
    }

    @Test
    void shouldCreateChatWithTitle() throws Exception {
        when(chatService.createChat("Plan my week", userId)).thenReturn(chatDTO);

        mockMvc.perform(post("/ai/agent/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"Plan my week\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chatId.toString()));
    }

    @Test
    void shouldCreateChatWithoutBody() throws Exception {
        when(chatService.createChat(isNull(), eq(userId))).thenReturn(chatDTO);

        mockMvc.perform(post("/ai/agent/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My chat"));
    }

    @Test
    void shouldSendMessageAndReturnReply() throws Exception {
        when(agentService.processMessage(chatId, "Hello", userId, null)).thenReturn("Hi! How can I help?");

        mockMvc.perform(post("/ai/agent/chats/" + chatId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userInput\": \"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hi! How can I help?"));
    }

    @Test
    void shouldForwardCurrentPageWhenProvided() throws Exception {
        when(agentService.processMessage(chatId, "create one", userId, "/habits")).thenReturn("Done!");

        mockMvc.perform(post("/ai/agent/chats/" + chatId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userInput\": \"create one\", \"currentPage\": \"/habits\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Done!"));
    }

    @Test
    void shouldRejectBlankMessage() throws Exception {
        mockMvc.perform(post("/ai/agent/chats/" + chatId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userInput\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldGetMessages() throws Exception {
        when(agentService.getMessages(chatId, userId))
                .thenReturn(List.of(new ChatMessageDTO("USER", "Hello"), new ChatMessageDTO("ASSISTANT", "Hi!")));

        mockMvc.perform(get("/ai/agent/chats/" + chatId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].text").value("Hi!"));
    }

    @Test
    void shouldDeleteChat() throws Exception {
        mockMvc.perform(delete("/ai/agent/chats/" + chatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Chat deleted successfully"));

        verify(chatService).deleteChat(chatId, userId);
    }

}
