package beyou.beyouapp.backend.domain.notebook;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookPageRepository extends JpaRepository<NotebookPage, UUID> {

    List<NotebookPage> findByUserIdAndKindOrderByCreatedAtAsc(UUID userId, NotebookPageKind kind);

    /** A topic's whole tree, topic row excluded. One indexed read; the tree is built in memory. */
    List<NotebookPage> findByTopicIdOrderByPositionAscCreatedAtAsc(UUID topicId);

    /**
     * Everything the user has. The home screen and the progress graph read this once, which at
     * the size of one person's notebook is cheaper than a recursive query per topic.
     */
    List<NotebookPage> findByUserId(UUID userId);

    List<NotebookPage> findByParentIdOrderByPositionAscCreatedAtAsc(UUID parentId);

    List<NotebookPage> findByIdIn(Collection<UUID> ids);

    List<NotebookPage> findTop20ByUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
            UUID userId, String title);

    long countByParentId(UUID parentId);
}
