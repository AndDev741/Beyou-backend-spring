package beyou.beyouapp.backend.integration.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatRepository;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Proves "reset the agent" is scoped to the caller — deleteAllChats takes the
 * authenticated user's id (never a request param), so one user resetting can't
 * touch another's chats or remembered context (no IDOR).
 */
class ChatServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ChatService chatService;
    @Autowired private ChatRepository chatRepository;
    @Autowired private UserRepository userRepository;

    private final List<UUID> userIds = new ArrayList<>();

    private User newUser(String context) {
        User user = new User();
        user.setName("Reset IT");
        user.setEmail("reset-it-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user.setUserContext(context);
        user = userRepository.saveAndFlush(user);
        userIds.add(user.getId());
        return user;
    }

    private void newChat(User user) {
        Chat chat = new Chat();
        chat.setUser(user);
        chat.setTitle("chat");
        chatRepository.saveAndFlush(chat);
    }

    @AfterEach
    void cleanUp() {
        userIds.forEach(id ->
                chatRepository.deleteAll(chatRepository.findAllByUserIdOrderByUpdatedAtDesc(id)));
        userIds.forEach(userRepository::deleteById);
        userIds.clear();
    }

    @Test
    void deleteAllChatsOnlyTouchesTheCallersOwnData() {
        User attacker = newUser("attacker memory");
        User victim = newUser("victim memory");
        newChat(attacker);
        newChat(attacker);
        newChat(victim);
        newChat(victim);

        chatService.deleteAllChats(attacker.getId());

        // Attacker's own chats + context are gone...
        assertTrue(chatRepository.findAllByUserIdOrderByUpdatedAtDesc(attacker.getId()).isEmpty());
        assertNull(userRepository.findById(attacker.getId()).orElseThrow().getUserContext());

        // ...the victim's are untouched.
        assertEquals(2, chatRepository.findAllByUserIdOrderByUpdatedAtDesc(victim.getId()).size());
        assertEquals("victim memory",
                userRepository.findById(victim.getId()).orElseThrow().getUserContext());
    }
}
