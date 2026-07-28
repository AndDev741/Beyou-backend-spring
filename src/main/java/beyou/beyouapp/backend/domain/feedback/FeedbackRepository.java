package beyou.beyouapp.backend.domain.feedback;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {
    List<Feedback> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * R21 — the ids of one user's submissions, and deliberately nothing else.
     *
     * Used by the account-deletion path to find the attachment directories on
     * disk. It must NOT return entities: this runs inside the transaction that
     * then removes the owner, and a managed {@code Feedback} pointing at a
     * just-removed {@code User} makes Hibernate fail the flush with
     * {@code TransientPropertyValueException}. Ids carry no such reference.
     */
    @Query("SELECT f.id FROM Feedback f WHERE f.user.id = :userId")
    List<UUID> findIdsByUserId(UUID userId);

    /**
     * The submission row, locked for update.
     *
     * Upload only. The per-submission attachment cap is a count followed by an
     * insert, and nothing serialises the two on its own — concurrent uploads
     * interleave and the cap stops holding. Locking the parent row is what
     * makes it hold; the read paths deliberately keep using
     * {@code findById}, because serving an image is no reason to make readers
     * queue.
     *
     * <p>Called AFTER the upload has already been authorized and decoded through
     * an unlocked {@code findById} — the extra read is deliberate, so the lock
     * covers the count-and-insert and not the image decode. See
     * {@code FeedbackAttachmentService#addAttachment}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Feedback f WHERE f.id = :id")
    Optional<Feedback> findByIdForUpdate(UUID id);

    /*
     * Admin inbox listing (R12). Each filter combination is its own query
     * rather than one query with nullable parameters: the parameters are enums,
     * and an `:param is null or ...` predicate leaves the driver guessing at
     * the bind type. Four explicit methods cost one branch in the service and
     * nothing at runtime.
     *
     * Every one of them JOIN FETCHes the submitter, because every listing row
     * carries one. `Feedback.user` is EAGER by JPA default, but Hibernate does
     * NOT turn an EAGER to-one into a join for a JPQL query — it issues one
     * extra select per row afterwards, so a full page costs up to a hundred
     * additional round trips. An INNER join is correct here: `user_id` is
     * NOT NULL (V9__feedback.sql), so it can never drop a row.
     *
     * Each carries an explicit countQuery: the derived count would otherwise
     * inherit the fetch join, which is illegal in a count query.
     *
     * Ordering comes from the Pageable, so the caller owns it in one place.
     */
    @Query(value = "SELECT f FROM Feedback f JOIN FETCH f.user",
            countQuery = "SELECT COUNT(f) FROM Feedback f")
    Page<Feedback> findAllWithSubmitter(Pageable pageable);

    @Query(value = "SELECT f FROM Feedback f JOIN FETCH f.user WHERE f.status = :status",
            countQuery = "SELECT COUNT(f) FROM Feedback f WHERE f.status = :status")
    Page<Feedback> findAllByStatusWithSubmitter(FeedbackStatus status, Pageable pageable);

    @Query(value = "SELECT f FROM Feedback f JOIN FETCH f.user WHERE f.category = :category",
            countQuery = "SELECT COUNT(f) FROM Feedback f WHERE f.category = :category")
    Page<Feedback> findAllByCategoryWithSubmitter(FeedbackCategory category, Pageable pageable);

    @Query(value = "SELECT f FROM Feedback f JOIN FETCH f.user "
            + "WHERE f.status = :status AND f.category = :category",
            countQuery = "SELECT COUNT(f) FROM Feedback f "
                    + "WHERE f.status = :status AND f.category = :category")
    Page<Feedback> findAllByStatusAndCategoryWithSubmitter(FeedbackStatus status,
                                                           FeedbackCategory category,
                                                           Pageable pageable);

    /**
     * R12 — the console's aggregate numbers, counted in the database in a
     * single grouped query rather than by measuring a loaded page. A state
     * with no rows is simply absent from the result; the service fills the zero.
     */
    @Query("SELECT f.status AS status, COUNT(f) AS total FROM Feedback f GROUP BY f.status")
    List<StatusCount> countGroupedByStatus();

    /** Projection for {@link #countGroupedByStatus()}. */
    interface StatusCount {
        FeedbackStatus getStatus();

        long getTotal();
    }
}
