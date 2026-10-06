package beyou.beyouapp.backend.domain.notebook.study;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One turn of a page's study-room chat. Separate from the assistant's chats on purpose: this
 * conversation belongs to a page, answers only from that page's sources, and has no tools.
 */
@Entity
@Table(name = "notebook_chat_messages")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookChatMessage {

    public static final String USER = "USER";
    public static final String ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "page_id", nullable = false)
    private UUID pageId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    @ToString.Exclude
    private String content;

    /** JSON list of citations on an answer; null on the person's own messages. */
    @Column(columnDefinition = "text")
    @ToString.Exclude
    private String citations;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
