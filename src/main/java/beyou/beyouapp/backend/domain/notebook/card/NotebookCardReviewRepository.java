package beyou.beyouapp.backend.domain.notebook.card;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookCardReviewRepository extends JpaRepository<NotebookCardReview, UUID> {

    List<NotebookCardReview> findByUserIdAndReviewDateAndXpPaidFalseOrderByReviewedAtAsc(
            UUID userId, LocalDate reviewDate);

    long countByUserIdAndReviewDateAndXpPaidTrue(UUID userId, LocalDate reviewDate);

    /** The days with a review, newest first, no further back than {@code since}. */
    @Query("select distinct r.reviewDate from NotebookCardReview r "
            + "where r.user.id = :userId and r.reviewDate >= :since order by r.reviewDate desc")
    List<LocalDate> reviewDaysSince(@Param("userId") UUID userId, @Param("since") LocalDate since);

    /** Every answer the user ever gave a flashcard, oldest first. For the data export. */
    List<NotebookCardReview> findByUserIdOrderByReviewedAtAsc(UUID userId);
}
