package beyou.beyouapp.backend.domain.notebook.cycle;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudyCycleTopicRepository extends JpaRepository<StudyCycleTopic, UUID> {

    List<StudyCycleTopic> findByCycleIdOrderByPositionAsc(UUID cycleId);

    List<StudyCycleTopic> findByCycleIdInOrderByPositionAsc(Collection<UUID> cycleIds);

    /**
     * Every subject the user's active cycles hold now, across all of them. What a page is matched
     * against when it is credited (the nearest one on its home chain wins), and what a new subject
     * must not overlap.
     */
    @Query("select t from StudyCycleTopic t, StudyCycle c where c.id = t.cycleId and c.user.id = :userId "
            + "and c.archivedAt is null and t.active = true")
    List<StudyCycleTopic> findActiveByUserId(@Param("userId") UUID userId);

    /** For the data export. */
    @Query("select t from StudyCycleTopic t, StudyCycle c where c.id = t.cycleId and c.user.id = :userId "
            + "order by t.position asc")
    List<StudyCycleTopic> findAllForExport(@Param("userId") UUID userId);
}
