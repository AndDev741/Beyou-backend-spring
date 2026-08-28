package beyou.beyouapp.backend.domain.routine.itemGroup;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lookup of an item group by its own id, across both subclasses.
 *
 * <p>Until the Focus Mode nothing needed this: checks reach a group through its routine
 * ({@code ItemGroupService.findHabitGroupByDTO}), because the check request names the routine. A
 * focus cycle or a micro-task names only the group, so the group has to be findable on its own.
 */
public interface ItemGroupRepository extends JpaRepository<ItemGroup, UUID> {

    /**
     * The group with its section and routine loaded, so the caller can read the owner in one
     * query rather than three lazy hops.
     */
    @Query("""
        SELECT g FROM ItemGroup g
        JOIN FETCH g.routineSection s
        JOIN FETCH s.routine r
        WHERE g.id = :id
        """)
    Optional<ItemGroup> findWithOwner(@Param("id") UUID id);
}
