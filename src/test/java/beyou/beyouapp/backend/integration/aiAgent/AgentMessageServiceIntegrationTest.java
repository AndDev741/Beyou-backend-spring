package beyou.beyouapp.backend.integration.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatRepository;
import beyou.beyouapp.backend.domain.aiAgent.chat.AgentMessageRepository;
import beyou.beyouapp.backend.domain.aiAgent.chat.AgentMessageService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/** Persistence + ordering for the display transcript, including the concurrent
 *  sequence-assignment race (#2) against a real Postgres. */
class AgentMessageServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AgentMessageService agentMessageService;
    @Autowired private AgentMessageRepository agentMessageRepository;
    @Autowired private ChatRepository chatRepository;
    @Autowired private UserRepository userRepository;

    // Track only what this class creates so teardown can be FK-safe and scoped —
    // the Postgres container is shared, so a broad deleteAll would wipe siblings.
    private final List<UUID> createdChatIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();

    private Chat newChat() {
        User user = new User();
        user.setName("Agent Msg IT");
        user.setEmail("agent-msg-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);

        Chat chat = new Chat();
        chat.setUser(user);
        chat.setTitle("IT chat");
        chat = chatRepository.saveAndFlush(chat);

        createdUserIds.add(user.getId());
        createdChatIds.add(chat.getId());
        return chat;
    }

    @AfterEach
    void cleanUp() {
        createdChatIds.forEach(chatRepository::deleteById); // cascades agent_message (V7 FK)
        createdUserIds.forEach(userRepository::deleteById);
        createdChatIds.clear();
        createdUserIds.clear();
    }

    @Test
    void recordTurnStoresUserThenAssistantAsOrderedSegments() {
        UUID chatId = newChat().getId();

        agentMessageService.recordTurn(chatId, "create a habit",
                List.of(AgentSegment.text("Done! "), AgentSegment.tool("createUserHabit", null, List.of("habits"))));

        List<AgentMessageDTO> messages = agentMessageService.getMessages(chatId);
        assertEquals(2, messages.size());
        assertEquals("USER", messages.get(0).role());
        assertEquals("create a habit", messages.get(0).segments().get(0).text());
        assertEquals("ASSISTANT", messages.get(1).role());
        assertEquals("tool", messages.get(1).segments().get(1).type());
        assertEquals("createUserHabit", messages.get(1).segments().get(1).tool());
    }

    @Test
    void concurrentTurnsGetDistinctContiguousSequenceIds() throws Exception {
        UUID chatId = newChat().getId();
        int turns = 12;

        ExecutorService pool = Executors.newFixedThreadPool(turns);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(turns);

        for (int i = 0; i < turns; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await(); // fire them as simultaneously as possible
                    agentMessageService.recordTurn(chatId, "msg " + n,
                            List.of(AgentSegment.text("reply " + n)));
                } catch (Exception ignored) {
                    // a failure would show up as a missing/duplicate seq below
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "turns did not finish in time");
        pool.shutdownNow();

        List<Long> seqs = agentMessageRepository.findByChatIdOrderBySequenceIdAsc(chatId)
                .stream().map(m -> m.getSequenceId()).collect(Collectors.toList());

        // 12 turns * 2 rows each, sequence ids exactly 0..23 with no gaps/dupes
        // (the per-chat advisory lock + unique constraint guarantee this).
        assertEquals(turns * 2, seqs.size());
        assertEquals(IntStream.range(0, turns * 2).mapToObj(Long::valueOf).collect(Collectors.toList()), seqs);
    }
}
