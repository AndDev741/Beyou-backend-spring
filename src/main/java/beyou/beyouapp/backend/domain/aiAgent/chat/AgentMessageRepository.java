package beyou.beyouapp.backend.domain.aiAgent.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, UUID> {
    List<AgentMessage> findByChatIdOrderBySequenceIdAsc(UUID chatId);
    long countByChatId(UUID chatId);
}
