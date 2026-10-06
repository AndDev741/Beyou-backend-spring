package beyou.beyouapp.backend.domain.notebook.ai.draft;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookRoadmapDraftRepository extends JpaRepository<NotebookRoadmapDraft, UUID> {

    List<NotebookRoadmapDraft> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    long countByUserId(UUID userId);

    /** A no-op for a draft that is gone or belongs to someone else. */
    @Modifying
    @Query("delete from NotebookRoadmapDraft d where d.id = :id and d.user.id = :userId")
    int deleteOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    /** At boot: the calls a restart cut off can never finish. */
    @Modifying
    @Query("update NotebookRoadmapDraft d set d.status = beyou.beyouapp.backend.domain.notebook.ai.draft"
            + ".RoadmapDraftStatus.FAILED, d.errorKey = :errorKey, d.updatedAt = :now "
            + "where d.status = beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftStatus.DRAFTING")
    int failInterrupted(@Param("errorKey") String errorKey, @Param("now") Instant now);
}
