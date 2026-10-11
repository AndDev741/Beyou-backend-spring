package beyou.beyouapp.backend.domain.notebook.cycle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudyCycleTopicRepository extends JpaRepository<StudyCycleTopic, UUID> {

    List<StudyCycleTopic> findByCycleIdOrderByPositionAsc(UUID cycleId);

    List<StudyCycleTopic> findByCycleIdInOrderByPositionAsc(Collection<UUID> cycleIds);

    /** The active row holding a topic, if any. At most one, by the partial unique index. */
    Optional<StudyCycleTopic> findByTopicIdAndActiveTrue(UUID topicId);

    List<StudyCycleTopic> findByTopicIdInAndActiveTrue(Collection<UUID> topicIds);

    /** For the data export. */
    @Query("select t from StudyCycleTopic t, StudyCycle c where c.id = t.cycleId and c.user.id = :userId "
            + "order by t.position asc")
    List<StudyCycleTopic> findAllForExport(@Param("userId") UUID userId);
}
