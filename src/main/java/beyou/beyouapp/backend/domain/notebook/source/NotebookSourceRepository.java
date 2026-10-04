package beyou.beyouapp.backend.domain.notebook.source;

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
public interface NotebookSourceRepository extends JpaRepository<NotebookSource, UUID> {

    List<NotebookSource> findByPageIdInOrderByCreatedAtAsc(Collection<UUID> pageIds);

    long countByPageId(UUID pageId);

    List<NotebookSource> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** Sources per page for every page the user has: [pageId, count]. */
    @Query("select s.pageId, count(s) from NotebookSource s where s.user.id = :userId group by s.pageId")
    List<Object[]> countByPage(@Param("userId") UUID userId);

    /**
     * Sources a restart left half-read. Their bytes are gone with the process (PDFs are never
     * stored), so they cannot be resumed; they are marked FAILED and the person adds them again.
     */
    @Modifying
    @Query("update NotebookSource s set s.status = beyou.beyouapp.backend.domain.notebook.source"
            + ".NotebookSourceStatus.FAILED, s.errorKey = :errorKey, s.updatedAt = :now "
            + "where s.status in (beyou.beyouapp.backend.domain.notebook.source.NotebookSourceStatus.PENDING, "
            + "beyou.beyouapp.backend.domain.notebook.source.NotebookSourceStatus.READING)")
    int failInterrupted(@Param("errorKey") String errorKey, @Param("now") Instant now);
}
