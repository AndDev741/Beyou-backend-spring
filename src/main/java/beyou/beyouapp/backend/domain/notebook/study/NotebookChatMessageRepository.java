package beyou.beyouapp.backend.domain.notebook.study;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookChatMessageRepository extends JpaRepository<NotebookChatMessage, UUID> {

    /** Newest first; the caller reverses it for display. */
    List<NotebookChatMessage> findByPageIdOrderByCreatedAtDesc(UUID pageId, Pageable page);

    void deleteByPageId(UUID pageId);

    List<NotebookChatMessage> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
