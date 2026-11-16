package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface SecondBrainQuestionRepository extends JpaRepository<SecondBrainQuestion, UUID> {

    /**
     * The question, if it is this user's, with the row locked until the transaction ends.
     * Answering and skipping take this, so a double submit closes the question once: the second
     * waits for the first to commit and then sees it closed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from SecondBrainQuestion q where q.id = :id and q.userId = :userId")
    Optional<SecondBrainQuestion> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** What the page shows: one status, oldest first. */
    List<SecondBrainQuestion> findByUserIdAndStatusOrderByCreatedAtAscIdAsc(
            UUID userId, SecondBrainQuestionStatus status);

    long countByUserIdAndStatus(UUID userId, SecondBrainQuestionStatus status);

    /** Whether this kind was ever asked, whatever became of it. The seed rule. */
    boolean existsByUserIdAndKind(UUID userId, SecondBrainQuestionKind kind);

    /** Whether this kind was asked since an instant. The daily check-in and the follow-up pause. */
    boolean existsByUserIdAndKindAndCreatedAtGreaterThanEqual(
            UUID userId, SecondBrainQuestionKind kind, Instant since);

    /** The most recent questions, answered or not, so the model does not repeat itself. */
    List<SecondBrainQuestion> findByUserIdOrderByCreatedAtDescIdAsc(UUID userId, Limit limit);

    /** Everything, for the account export, oldest first. */
    List<SecondBrainQuestion> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);
}
