package beyou.beyouapp.backend.domain.notebook.card;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookCardRepository extends JpaRepository<NotebookCard, UUID> {

    List<NotebookCard> findByPageIdOrderByCreatedAtAsc(UUID pageId);

    long countByPageId(UUID pageId);

    long countByPageIdAndDueOnLessThanEqual(UUID pageId, LocalDate day);

    /** The review queue, oldest due first. */
    List<NotebookCard> findByUserIdAndDueOnLessThanEqualOrderByDueOnAscCreatedAtAsc(UUID userId, LocalDate day);

    List<NotebookCard> findByUserIdAndPageIdInAndDueOnLessThanEqualOrderByDueOnAscCreatedAtAsc(
            UUID userId, Collection<UUID> pageIds, LocalDate day);

    /** Due cards per page, for every page the user has: [pageId, count]. */
    @Query("select c.pageId, count(c) from NotebookCard c "
            + "where c.user.id = :userId and c.dueOn <= :day group by c.pageId")
    List<Object[]> countDueByPage(@Param("userId") UUID userId, @Param("day") LocalDate day);

    /** Every card per page, for every page the user has: [pageId, count]. */
    @Query("select c.pageId, count(c) from NotebookCard c where c.user.id = :userId group by c.pageId")
    List<Object[]> countByPage(@Param("userId") UUID userId);

    List<NotebookCard> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
