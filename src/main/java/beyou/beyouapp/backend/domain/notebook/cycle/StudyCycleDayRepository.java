package beyou.beyouapp.backend.domain.notebook.cycle;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudyCycleDayRepository extends JpaRepository<StudyCycleDay, StudyCycleDay.Key> {

    List<StudyCycleDay> findByCycleIdIn(Collection<UUID> cycleIds);

    List<StudyCycleDay> findByCycleId(UUID cycleId);

    /** For the data export: every day row of the user's cycles, one statement. */
    @Query("select d from StudyCycleDay d, StudyCycle c where c.id = d.cycleId and c.user.id = :userId")
    List<StudyCycleDay> findAllForExport(@Param("userId") UUID userId);
}
