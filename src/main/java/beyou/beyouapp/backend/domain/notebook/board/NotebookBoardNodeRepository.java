package beyou.beyouapp.backend.domain.notebook.board;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookBoardNodeRepository extends JpaRepository<NotebookBoardNode, UUID> {

    List<NotebookBoardNode> findByBoardPageIdOrderByCreatedAtAsc(UUID boardPageId);

    /** Every board that shows this page: the question each status change asks on its way up. */
    List<NotebookBoardNode> findByPageId(UUID pageId);

    /**
     * Every PAGE node the user has, on every board. The progress graph is built from this in one
     * read, which is cheap at the size of one person's notebook and keeps the recursion in memory.
     */
    List<NotebookBoardNode> findByUserIdAndKind(UUID userId, NotebookNodeKind kind);

    Optional<NotebookBoardNode> findByBoardPageIdAndPageId(UUID boardPageId, UUID pageId);

    List<NotebookBoardNode> findByBoardPageIdIn(Collection<UUID> boardPageIds);

    long countByBoardPageId(UUID boardPageId);
}
