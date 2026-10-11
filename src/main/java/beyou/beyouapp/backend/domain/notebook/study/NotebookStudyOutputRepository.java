package beyou.beyouapp.backend.domain.notebook.study;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookStudyOutputRepository extends JpaRepository<NotebookStudyOutput, UUID> {

    List<NotebookStudyOutput> findByPageIdAndKindNotOrderByCreatedAtDesc(UUID pageId, StudyOutputKind kind);

    Optional<NotebookStudyOutput> findFirstByPageIdAndKindOrderByCreatedAtDesc(UUID pageId, StudyOutputKind kind);

    void deleteByPageIdAndKind(UUID pageId, StudyOutputKind kind);

    /** Everything the study room ever produced for the user, oldest first. For the data export. */
    List<NotebookStudyOutput> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /**
     * The pages with a passed quiz, any of their quizzes. A study cycle's GOAL block that asks for
     * the quiz reads this: the page's concept counts as understood once one quiz on it passed.
     */
    @Query("select distinct o.pageId from NotebookStudyOutput o where o.user.id = :userId "
            + "and o.kind = beyou.beyouapp.backend.domain.notebook.study.StudyOutputKind.QUIZ "
            + "and o.passedAt is not null")
    Set<UUID> findPassedQuizPages(@Param("userId") UUID userId);
}
