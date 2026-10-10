package beyou.beyouapp.backend.domain.notebook.board;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookBoardEdgeRepository extends JpaRepository<NotebookBoardEdge, UUID> {

    List<NotebookBoardEdge> findByBoardPageId(UUID boardPageId);

    List<NotebookBoardEdge> findByBoardPageIdIn(Collection<UUID> boardPageIds);

    boolean existsBySourceNodeIdAndTargetNodeId(UUID sourceNodeId, UUID targetNodeId);

    /** Every edge on every board the user has, for the data export. */
    List<NotebookBoardEdge> findByUserId(UUID userId);
}
