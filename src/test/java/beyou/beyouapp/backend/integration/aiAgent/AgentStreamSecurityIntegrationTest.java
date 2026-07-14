package beyou.beyouapp.backend.integration.aiAgent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatRepository;
import beyou.beyouapp.backend.security.SecurityConfig;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

/**
 * The streaming endpoint must sit behind auth AND enforce chat ownership on the
 * initial request dispatch — before any emitter opens or the LLM is called.
 * Full security filters ON (unlike AiAgentControllerTest which disables them).
 */
@AutoConfigureMockMvc
@Import({ SecurityConfig.class })
class AgentStreamSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    private static final String PASSWORD = "TestPassword1!";
    private UUID ownerChatId;
    private String otherUserToken;
    // Track only what this class creates for a scoped, FK-safe teardown — the
    // Postgres container is shared, so a broad deleteAll would wipe siblings
    // (and leftover chats/tokens would break another class's user deleteAll).
    private final List<UUID> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        User owner = registerAndVerify("owner-" + UUID.randomUUID() + "@test.com");
        Chat chat = new Chat();
        chat.setUser(owner);
        chat.setTitle("owner's chat");
        ownerChatId = chatRepository.saveAndFlush(chat).getId();

        String otherEmail = "other-" + UUID.randomUUID() + "@test.com";
        registerAndVerify(otherEmail);
        otherUserToken = login(otherEmail);
    }

    @AfterEach
    void cleanUp() {
        chatRepository.deleteById(ownerChatId);
        createdUserIds.forEach(id ->
                refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(id)));
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    void rejectsUnauthenticatedStream() throws Exception {
        mockMvc.perform(post("/ai/agent/chats/" + ownerChatId + "/stream")
                        .content("{\"userInput\":\"hello\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorKey").value("JWT_NOT_FOUND"));
    }

    @Test
    void rejectsStreamingIntoAnotherUsersChat() throws Exception {
        // getChat's ownership check runs first, so this fails before any emitter
        // opens or DeepSeek is called — no API key needed for this path.
        mockMvc.perform(post("/ai/agent/chats/" + ownerChatId + "/stream")
                        .header("authorization", "Bearer " + otherUserToken)
                        .content("{\"userInput\":\"hello\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("CHAT_NOT_OWNED"));
    }

    private User registerAndVerify(String email) {
        userService.registerUser(new UserRegisterDTO("test", email, PASSWORD));
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private String login(String email) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Access-Token");
    }
}
