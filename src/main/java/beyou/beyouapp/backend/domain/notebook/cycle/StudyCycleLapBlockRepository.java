package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudyCycleLapBlockRepository extends JpaRepository<StudyCycleLapBlock, UUID> {

    List<StudyCycleLapBlock> findByCycleIdAndLapNumber(UUID cycleId, int lapNumber);

    /**
     * The rows of each cycle's CURRENT lap, for several cycles in one statement. Rows of closed
     * laps stay in the table as history, so filtering by cycle alone would read every lap ever run.
     */
    @Query("select r from StudyCycleLapBlock r, StudyCycle c "
            + "where c.id = r.cycleId and r.lapNumber = c.lapNumber and c.id in :cycleIds")
    List<StudyCycleLapBlock> findCurrentLaps(@Param("cycleIds") Collection<UUID> cycleIds);

    /** Rows closed in a window, any lap: "done today" and the history's understood concepts. */
    List<StudyCycleLapBlock> findByCycleIdInAndClosedAtGreaterThanEqualAndClosedAtLessThan(
            Collection<UUID> cycleIds, Instant from, Instant to);

    /** For the data export. */
    @Query("select r from StudyCycleLapBlock r, StudyCycle c where c.id = r.cycleId and c.user.id = :userId "
            + "order by r.lapNumber asc")
    List<StudyCycleLapBlock> findAllForExport(@Param("userId") UUID userId);
}
