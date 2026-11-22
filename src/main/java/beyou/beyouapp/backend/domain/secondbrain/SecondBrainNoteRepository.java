package beyou.beyouapp.backend.domain.secondbrain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SecondBrainNoteRepository extends JpaRepository<SecondBrainNote, UUID> {

    /**
     * The note, if it is this user's. Ownership is part of the lookup, so someone else's note
     * reads exactly like one that does not exist.
     */
    Optional<SecondBrainNote> findByIdAndUserId(UUID id, UUID userId);

    /** Every note of the account, oldest first: the page, the prompts and the export. */
    List<SecondBrainNote> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    /**
     * The notes one live thing has, oldest first, for the 30-per-thing trim. A category's own
     * notes only: notes moved there from a deleted habit carry a former type and are a subject
     * of their own.
     */
    List<SecondBrainNote> findByUserIdAndSubjectTypeAndSubjectIdAndFormerTypeIsNullOrderByCreatedAtAscIdAsc(
            UUID userId, SecondBrainSubjectType subjectType, UUID subjectId);

    /** A deleted habit or goal hands its notes to a category, keeping what it was and its name. */
    @Modifying
    @Query("update SecondBrainNote n set n.subjectType = beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType.CATEGORY, "
            + "n.subjectId = :categoryId, n.formerType = :type, n.subjectName = :name "
            + "where n.userId = :userId and n.subjectType = :type and n.subjectId = :thingId")
    int moveToCategory(@Param("userId") UUID userId, @Param("type") SecondBrainSubjectType type,
                       @Param("thingId") UUID thingId, @Param("categoryId") UUID categoryId,
                       @Param("name") String name);

    /** A deleted habit, goal or routine with nowhere to hand its notes to: they turn LOOSE. */
    @Modifying
    @Query("update SecondBrainNote n set n.subjectType = beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType.LOOSE, "
            + "n.subjectId = null, n.formerType = :type, n.subjectName = :name "
            + "where n.userId = :userId and n.subjectType = :type and n.subjectId = :thingId")
    int loosen(@Param("userId") UUID userId, @Param("type") SecondBrainSubjectType type,
               @Param("thingId") UUID thingId, @Param("name") String name);

    /**
     * The first half of a category delete: its own notes take its last name, as a deleted
     * habit's do. Notes moved there earlier keep the name of the thing they were about.
     */
    @Modifying
    @Query("update SecondBrainNote n set n.subjectName = :name "
            + "where n.userId = :userId and n.subjectType = beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType.CATEGORY "
            + "and n.subjectId = :categoryId and n.formerType is null")
    int renameCategoryNotes(@Param("userId") UUID userId, @Param("categoryId") UUID categoryId,
                            @Param("name") String name);

    /**
     * The second half: every note under the category turns LOOSE, its own and the ones moved
     * there, each keeping its former type.
     */
    @Modifying
    @Query("update SecondBrainNote n set n.subjectType = beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType.LOOSE, "
            + "n.subjectId = null "
            + "where n.userId = :userId and n.subjectType = beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType.CATEGORY "
            + "and n.subjectId = :categoryId")
    int loosenCategory(@Param("userId") UUID userId, @Param("categoryId") UUID categoryId);
}
