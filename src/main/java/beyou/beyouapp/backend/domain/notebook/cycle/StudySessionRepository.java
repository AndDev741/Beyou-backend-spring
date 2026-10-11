package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StudySessionRepository extends JpaRepository<StudySession, UUID> {

    @Query("select s from StudySession s where s.user.id = :userId and s.studiedOn between :from and :to "
            + "order by s.createdAt asc")
    List<StudySession> findRange(@Param("userId") UUID userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Every session the user logged, oldest first, for the data export. */
    @Query("select s from StudySession s where s.user.id = :userId order by s.studiedOn asc, s.createdAt asc")
    List<StudySession> findAllForExport(@Param("userId") UUID userId);
}
