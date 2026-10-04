package beyou.beyouapp.backend.domain.notebook.board;

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
 * "Study this before that" between two nodes of the same board.
 *
 * <p>Order only. An edge does not lock anything and does not feed progress: a person can
 * open a node whose prerequisites are unfinished, and the clients only use edges to draw the
 * board and to sort the mobile path into levels.
 */
@Entity
@Table(name = "notebook_board_edges")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookBoardEdge {

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

    @Column(name = "source_node_id", nullable = false)
    private UUID sourceNodeId;

    @Column(name = "target_node_id", nullable = false)
    private UUID targetNodeId;
}
