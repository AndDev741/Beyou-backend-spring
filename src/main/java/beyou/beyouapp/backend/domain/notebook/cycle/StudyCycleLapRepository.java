package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudyCycleLapRepository extends JpaRepository<StudyCycleLap, UUID> {

    /** The most recent closed laps, newest first. */
    List<StudyCycleLap> findByCycleIdOrderByNumberDesc(UUID cycleId, Pageable page);

    List<StudyCycleLap> findByCycleIdAndClosedAtGreaterThanEqualAndClosedAtLessThan(
            UUID cycleId, Instant from, Instant to);

    List<StudyCycleLap> findByCycleIdInAndClosedAtGreaterThanEqualAndClosedAtLessThan(
            Collection<UUID> cycleIds, Instant from, Instant to);

    /** For the data export. */
    @Query("select l from StudyCycleLap l, StudyCycle c where c.id = l.cycleId and c.user.id = :userId "
            + "order by l.number asc")
    List<StudyCycleLap> findAllForExport(@Param("userId") UUID userId);
}
