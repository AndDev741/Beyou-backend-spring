package beyou.beyouapp.backend.domain.notebook.ai.draft;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A roadmap draft for "New topic with AI", kept until the topic is created or the draft deleted.
 *
 * <p>{@code request}, {@code result} and {@code choices} are JSON: what was asked for, the
 * drafted nodes, and which of them the person kept or chose to link. See V35 for why the draft
 * is stored at all.
 *
 * <p>{@code @DynamicUpdate} because the background job and the person both write this row: the
 * job its result, the person their ticks. Each UPDATE carries only what that writer changed.
 */
@Entity
@Table(name = "notebook_roadmap_drafts")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookRoadmapDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoadmapDraftStatus status;

    @Column(nullable = false, columnDefinition = "text")
    @ToString.Exclude
    private String request;

    @Column(columnDefinition = "text")
    @ToString.Exclude
    private String result;

    @Column(columnDefinition = "text")
    @ToString.Exclude
    private String choices;

    @Column(name = "error_key", length = 64)
    private String errorKey;

    /** When the current (or last) model call began. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
