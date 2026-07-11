package beyou.beyouapp.backend.domain.aiAgent.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<Chat, UUID> {
    List<Chat> findAllByUserIdOrderByUpdatedAtDesc(UUID userId);
}