package beyou.beyouapp.backend.domain.aiAgent.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, UUID> {
    List<AgentMessage> findByChatIdOrderBySequenceIdAsc(UUID chatId);
    long countByChatId(UUID chatId);

    /**
     * Serializes transcript writes for one chat across concurrent turns (two
     * tabs, double-submit, retry overlap) so sequence assignment stays atomic.
     * Transaction-scoped: released on commit/rollback. Different chats don't block.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:chatId))", nativeQuery = true)
    void lockChatForTranscript(@Param("chatId") String chatId);
}
