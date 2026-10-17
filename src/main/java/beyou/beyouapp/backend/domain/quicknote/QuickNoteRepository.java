package beyou.beyouapp.backend.domain.quicknote;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface QuickNoteRepository extends JpaRepository<QuickNote, UUID> {

    /**
     * The note, if it is this user's. Ownership is part of the lookup rather than a check after
     * it, so a note that exists but belongs to someone else reads exactly like one that does not.
     */
    Optional<QuickNote> findByIdAndUserId(UUID id, UUID userId);

    /**
     * The same lookup, with the row locked until the transaction ends. Converting a note to a
     * task takes this, so a second submit waits for the first to commit and then sees the note
     * already converted, instead of both reading "not converted yet" and making two tasks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from QuickNote n where n.id = :id and n.user.id = :userId")
    Optional<QuickNote> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** The board, back to front, oldest first among equals. At most {@code QuickNote.MAX_ACTIVE} rows. */
    List<QuickNote> findByUserIdAndArchivedAtIsNullOrderByZAscCreatedAtAsc(UUID userId);

    /** The archived list, most recently archived first, capped by the caller. */
    List<QuickNote> findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDescIdAsc(UUID userId, Limit limit);

    /** The active notes among {@code ids} that are this user's. A layout save compares the size. */
    List<QuickNote> findByUserIdAndArchivedAtIsNullAndIdIn(UUID userId, Collection<UUID> ids);

    /** Everything, archived included, for the account export, oldest first. */
    List<QuickNote> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    /**
     * Serialises the writes that decide something from the whole board: create and restore
     * (the limit, the next z, the first free cell) and tidy (every position).
     *
     * <p>Each of those reads the active notes and then writes what the read decided, and two
     * running at once would both read before either commits: a double Enter lands two notes on
     * the same cell, and two creates at 199 make 201. An advisory lock rather than a row lock,
     * because an empty board has no row to lock. Released at commit. {@code hashtext} can collide;
     * two users sharing a key wait for one save, and nothing else.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    void lockBoard(@Param("key") String key);
}
