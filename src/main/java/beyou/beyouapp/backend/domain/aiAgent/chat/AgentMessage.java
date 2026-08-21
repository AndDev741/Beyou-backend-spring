package beyou.beyouapp.backend.domain.aiAgent.chat;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One display-history row for an agent chat. Cascade delete is owned by the DB
 * FK (V7), so there's no JPA relationship to the Chat — just the id.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "agent_message")
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "chat_id", nullable = false)
    private UUID chatId;

    @Column(nullable = false, length = 10)
    private String role;

    /** JSON array of AgentSegment — the ordered transcript of this turn. */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "sequence_id", nullable = false)
    private long sequenceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Which provider in the fallback chain produced this turn, or null when it is a
     * user turn or predates the column. Recorded per turn rather than inferred from
     * {@code beyou.ai.llm.calls}, because a counter says how often each provider was
     * used and never which one produced the answer you are looking at.
     */
    @Column(length = 32)
    private String provider;

    public AgentMessage(UUID chatId, String role, String content, long sequenceId) {
        this(chatId, role, content, sequenceId, null);
    }

    public AgentMessage(UUID chatId, String role, String content, long sequenceId, String provider) {
        this.chatId = chatId;
        this.role = role;
        this.content = content;
        this.sequenceId = sequenceId;
        this.provider = provider;
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
