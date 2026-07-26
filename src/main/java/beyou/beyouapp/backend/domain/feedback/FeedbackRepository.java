package beyou.beyouapp.backend.domain.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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

    /*
     * Admin inbox listing (R12). Each filter combination is its own derived
     * query rather than one query with nullable parameters: the parameters are
     * enums, and an `:param is null or ...` predicate leaves the driver
     * guessing at the bind type. Four explicit methods cost one branch in the
     * service and nothing at runtime.
     *
     * Ordering comes from the Pageable, so the caller owns it in one place.
     */
    Page<Feedback> findAllByStatus(FeedbackStatus status, Pageable pageable);

    Page<Feedback> findAllByCategory(FeedbackCategory category, Pageable pageable);

    Page<Feedback> findAllByStatusAndCategory(FeedbackStatus status, FeedbackCategory category, Pageable pageable);

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
