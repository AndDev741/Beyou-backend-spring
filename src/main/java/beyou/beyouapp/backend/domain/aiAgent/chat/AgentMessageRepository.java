package beyou.beyouapp.backend.domain.aiAgent.chat;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, UUID> {
    List<AgentMessage> findByChatIdOrderBySequenceIdAsc(UUID chatId);

    /**
     * Every stored transcript for a set of chats, in one round trip.
     *
     * <p>The data export reads every conversation an account has, and doing that one
     * chat at a time made the cost of the download grow with how much someone had
     * talked to the assistant — the heaviest users paying the most to leave.
     * Ordered by chat first so the caller can group the rows without re-sorting,
     * then by sequence so each conversation reads in the order it happened.
     */
    List<AgentMessage> findByChatIdInOrderByChatIdAscSequenceIdAsc(Collection<UUID> chatIds);
    long countByChatId(UUID chatId);

    /**
     * Serializes transcript writes for one chat across concurrent turns (two
     * tabs, double-submit, retry overlap) so sequence assignment stays atomic.
     * Transaction-scoped: released on commit/rollback. Different chats don't block.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:chatId))", nativeQuery = true)
    void lockChatForTranscript(@Param("chatId") String chatId);
}
