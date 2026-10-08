package beyou.beyouapp.backend.domain.notebook;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Exact title, any case. The assistant names pages the way the person does. */
    List<NotebookPage> findByUserIdAndTitleIgnoreCase(UUID userId, String title);

    long countByParentId(UUID parentId);

    /**
     * Records an open without loading the row as dirty. Opening a page is a read that the client
     * repeats while the person writes, so it must never be the request that saves the page.
     */
    @Modifying
    @Query("update NotebookPage p set p.lastOpenedAt = :at where p.id = :id")
    int markOpened(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Writes the document only if it is still at revision {@code read}, and moves it to
     * {@code read + 1}. Returns 0 when someone wrote it since: the caller read an old document.
     * Clears the persistence context, so a page loaded earlier in the transaction is read again
     * rather than served stale.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NotebookPage p set p.content = :content, p.contentText = :text, p.updatedAt = :at, "
            + "p.contentRevision = p.contentRevision + 1 where p.id = :id and p.contentRevision = :read")
    int writeContentIfAt(@Param("id") UUID id, @Param("content") String content, @Param("text") String text,
            @Param("at") Instant at, @Param("read") long read);
}
