package beyou.beyouapp.backend.domain.notebook.board;

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
 * One box on a page's roadmap board.
 *
 * <p>A PAGE node opens {@code pageId}. Usually that page is a child of the board's page, made
 * when the node was; a node can also point at a page whose home is in another topic (the AI
 * draft's "Link it"), and then the node is the only thing tying it to this board. A SECTION is
 * a labelled band drawn behind nodes and opens nothing.
 *
 * <p>Ids, not associations, for the same reason as {@code NotebookPage.parentId}: the board is
 * loaded whole and joined in memory.
 */
@Entity
@Table(name = "notebook_board_nodes")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookBoardNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "board_page_id", nullable = false)
    private UUID boardPageId;

    @Column(name = "page_id")
    private UUID pageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotebookNodeKind kind;

    @Column(length = 255)
    private String label;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    private Double width;

    private Double height;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
