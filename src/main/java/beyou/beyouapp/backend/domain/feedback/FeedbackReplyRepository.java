package beyou.beyouapp.backend.domain.feedback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface FeedbackReplyRepository extends JpaRepository<FeedbackReply, UUID> {

    /**
     * A submission's reply thread, oldest first, with its authors.
     *
     * {@code FeedbackReply.author} is another EAGER {@code @ManyToOne} that a
     * JPQL query would resolve with one extra select per reply, and the admin
     * detail view reads the author's name off every one of them. LEFT, not
     * INNER: {@code author_id} is nullable on purpose (V12__feedback_reply.sql
     * sets it null when the admin account goes), so an inner join would hide
     * replies from departed staff.
     */
    @Query("SELECT r FROM FeedbackReply r LEFT JOIN FETCH r.author "
            + "WHERE r.feedback.id = :feedbackId ORDER BY r.createdAt ASC")
    List<FeedbackReply> findAllByFeedbackIdOrderByCreatedAtAsc(UUID feedbackId);
}
