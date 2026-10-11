package beyou.beyouapp.backend.domain.notebook.cycle;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudyCycleBlockRepository extends JpaRepository<StudyCycleBlock, UUID> {

    List<StudyCycleBlock> findByCycleIdOrderByPositionAsc(UUID cycleId);

    List<StudyCycleBlock> findByCycleIdInOrderByPositionAsc(Collection<UUID> cycleIds);

    /** For the data export. */
    @Query("select b from StudyCycleBlock b, StudyCycle c where c.id = b.cycleId and c.user.id = :userId "
            + "order by b.position asc")
    List<StudyCycleBlock> findAllForExport(@Param("userId") UUID userId);
}
