package beyou.beyouapp.backend.domain.notebook.source;

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
 * Something the study AI may quote: a PDF, a web page, or pasted text, attached to a page.
 *
 * <p>Attached to a page and seen from below it: a source on the topic is readable from every
 * node in it, and a source on a node only from that node and what is under it. The study room's
 * scope is the page plus its ancestors (see {@code NotebookSourceService.scopeOf}).
 *
 * <p>The text lives in {@code notebook_source_chunks}, written and read with JdbcTemplate (see
 * {@code SourceChunkStore}). The file itself is never stored.
 */
@Entity
@Table(name = "notebook_sources")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookSource {

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
    private NotebookSourceKind kind;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 2048)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotebookSourceStatus status;

    @Column(nullable = false)
    private int progress;

    @Column(name = "error_key", length = 64)
    private String errorKey;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
