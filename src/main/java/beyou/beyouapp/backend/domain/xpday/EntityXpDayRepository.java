package beyou.beyouapp.backend.domain.xpday;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads, upserts and deletes over the per-day XP history. */
public interface EntityXpDayRepository extends JpaRepository<EntityXpDay, UUID> {

    /**
     * Adds a delta to one entity's bucket for one day, creating the bucket if this is
     * the first XP it earned that day.
     *
     * <p>Native and an upsert on purpose. Read-modify-write would be a race: two
     * check-ins landing together both read the same bucket, both add their own XP to
     * it, and the second write erases the first. {@code ON CONFLICT ... DO UPDATE} with
     * the unique constraint as its target makes the addition happen inside the database,
     * so concurrent check-ins queue on the row instead of overwriting each other.
     *
     * <p>{@code EXCLUDED.xp} is the delta this call brought, not the total — the sum is
     * the point.
     */
    @Modifying
    @Query(value = """
            INSERT INTO entity_xp_day (id, user_id, owner_type, owner_id, day, xp)
            VALUES (gen_random_uuid(), :userId, :ownerType, :ownerId, :day, :xp)
            ON CONFLICT ON CONSTRAINT uk_entity_xp_day_owner_day
            DO UPDATE SET xp = entity_xp_day.xp + EXCLUDED.xp
            """, nativeQuery = true)
    void addXp(@Param("userId") UUID userId,
            @Param("ownerType") String ownerType,
            @Param("ownerId") UUID ownerId,
            @Param("day") LocalDate day,
            @Param("xp") double xp);

    /**
     * One user's whole history across a window, every owner type at once.
     *
     * <p>One query rather than one per entity: a dashboard asking for the week wants
     * the best category, the worst category and every card on the categories page, and
     * that is the same window read once. The caller groups by owner.
     */
    List<EntityXpDay> findByUserIdAndDayBetweenOrderByDayAsc(UUID userId, LocalDate from, LocalDate to);

    /**
     * Drops one entity's entire series, for the delete paths.
     *
     * <p>A bulk delete rather than the derived {@code deleteBy...}, which would load
     * every row into the persistence context to remove it one at a time. Same shape as
     * {@code EntityCheckDayRepository.deleteAllByOwner}, and needed for the same reason:
     * {@code owner_id} has no foreign key, so nothing else will clean up after a
     * deleted habit, routine or category.
     */
    @Modifying
    @Query("delete from EntityXpDay e where e.ownerType = :ownerType and e.ownerId = :ownerId")
    int deleteAllByOwner(@Param("ownerType") XpDayOwnerType ownerType, @Param("ownerId") UUID ownerId);
}
