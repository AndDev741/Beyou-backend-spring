package beyou.beyouapp.backend.domain.notebook.study;

import java.time.Instant;
import java.util.UUID;

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
 * Something the studio made for a page: an overview, a summary, a study guide or a quiz.
 *
 * <p>{@code content} is JSON in every case ({@code StudyOutputs} reads and writes it), so a
 * summary keeps its citations next to its markdown and a quiz keeps its answers on the server.
 */
@Entity
@Table(name = "notebook_study_outputs")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookStudyOutput {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StudyOutputKind kind;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    @ToString.Exclude
    private String content;

    private Integer score;

    private Integer total;

    @Column(name = "passed_at")
    private Instant passedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
